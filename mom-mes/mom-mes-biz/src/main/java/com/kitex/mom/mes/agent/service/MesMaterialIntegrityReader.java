package com.kitex.mom.mes.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.agent.dto.MesMaterialIntegrityDTO;
import com.kitex.mom.mes.agent.dto.MesReportQuantityConsistencyDTO;
import com.kitex.mom.mes.dto.MesMaterialConsumptionTraceDTO.Detail;
import com.kitex.mom.mes.dto.MesMaterialConsumptionTraceDTO.Fact;
import com.kitex.mom.mes.dto.MesMaterialConsumptionTraceDTO.Row;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.mapper.MesProductionFeedBatchMapper;
import com.kitex.mom.mes.mapper.MesProductionFeedOutputMapper;
import com.kitex.mom.mes.mapper.MesProductionFeedConsumptionMapper;
import com.kitex.mom.mes.mapper.MesProductionFeedShareMapper;
import com.kitex.mom.mes.service.impl.MesProductionFeedLedgerFacts;
import com.kitex.mom.mes.mapper.MesWarehouseFeedMapper;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import com.kitex.mom.mes.service.impl.MesMaterialConsumptionTraceService;
import com.kitex.mom.mes.service.impl.MesNgMaterialPolicyCodec;
import com.kitex.mom.mes.mapper.MesNgMaterialPolicyMapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 复用原实耗追溯解析并核对恢复净额、逐投料已用量；不写生产账或领退料状态。
 * GOOD纠正逐原source，NG恢复逐原allocation集合，组合恢复逐原consumption核验。
 */
@Service
@RequiredArgsConstructor
public class MesMaterialIntegrityReader {
	public static final String VERSION = "MES_MATERIAL_INTEGRITY_V1";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final ObjectMapper POLICY_JSON = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
	private static final MesNgMaterialPolicyCodec POLICY_CODEC = new MesNgMaterialPolicyCodec();
	private final MesActualFactoryAccessService access;
	private final MesTaskMapper tasks;
	private final MesWarehouseFeedMapper feeds;
	private final MesMaterialConsumptionTraceService trace;
	private final MesProductionFeedBatchMapper batches;
	private final MesProductionFeedOutputMapper batchOutputs;
	private final MesProductionFeedConsumptionMapper batchConsumptions;
	private final MesProductionFeedShareMapper batchShares;
	private final MesProductionFeedLedgerFacts batchFacts;

	/**
	 * 独立只读快照；缺证异常不会将调用方的兼容统计事务标记为rollback-only。
	 */
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
	public MesMaterialIntegrityDTO.Evidence read(String factory, String taskCode) {
		require(Objects.equals(factory, access.require("read")), "物料核验工厂与实际工厂不一致");
		Long tenant = TenantContextHolder.getTenantId();
		var taskRows = tasks.selectList(new LambdaQueryWrapper<MesTaskEntity>()
				.eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
				.eq(MesTaskEntity::getCode, taskCode).last("LIMIT 2"));
		require(taskRows.size() == 1, "当前实际组织未找到唯一工单，不能核定实耗范围");
		List<Detail> details = trace.workOrderEvidence(taskCode);
		var feedRows = feeds.selectList(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
				.eq(MesWarehouseFeedEntity::getTenantId, tenant).eq(MesWarehouseFeedEntity::getFactoryCode, factory)
				.eq(MesWarehouseFeedEntity::getTaskCode, taskCode).orderByAsc(MesWarehouseFeedEntity::getId)
				.last("LIMIT 201"));
		require(feedRows.size() <= 200, "工单投料超过200条完整核验上限，未截断形成正常结论");
		var checks = new Checks();
		List<MesMaterialIntegrityDTO.Source> sources = new ArrayList<>();
		Map<String, BigDecimal> netByFeed = new HashMap<>();
		Set<String> unprovenFeeds = new HashSet<>();
		Set<String> currentFeeds = new HashSet<>();
		feedRows.forEach(feed -> currentFeeds.add(feed.getId().toString()));
		List<String> gaps = new ArrayList<>();
		Set<String> verifiedBatches = new HashSet<>();
		Map<String, PolicyEvidence> verifiedPolicies = new HashMap<>();
		for (Detail detail : details) {
			Row row = detail.getRecord();
			require(factory.equals(detail.getFactoryCode()) && tenant.toString().equals(detail.getTenantId())
					&& taskCode.equals(row.getTaskCode()), "原实耗取证返回了其他实际组织或工单");
			var item = new MesMaterialIntegrityDTO.Source().setOriginal(row).setIssues(new ArrayList<>())
					.setDataGaps(new ArrayList<>()).setRestorationRefs(new ArrayList<>());
			validate(checks, "SOURCE_LINK", item, () -> source(detail));
			BigDecimal original = validate(checks, "MATERIAL_QUANTITY", item, () -> quantity(detail));
			validate(checks, "OUTPUT_SHARE", item, () -> shares(detail, verifiedBatches));
			BigDecimal restored = original == null ? null
					: validate(checks, "RESTORE_PROOF", item,
					() -> restored(detail, details, original, verifiedPolicies));
			if (restored != null) {
				item.setRestoredQuantity(text(restored));
			}
			if (original != null && restored != null && item.getIssues().isEmpty() && item.getDataGaps().isEmpty()) {
				BigDecimal net = original.subtract(restored);
				item.setNetQuantity(text(net));
				detail.getRestores()
						.forEach(fact -> item.getRestorationRefs().add(fact.getTableName() + ':' + fact.getSourceId()));
				if (row.getFeedId() != null) {
					netByFeed.merge(row.getFeedId(), net, BigDecimal::add);
				}
			} else if (row.getFeedId() != null) {
				unprovenFeeds.add(row.getFeedId());
			}
			if (row.getFeedId() != null && !currentFeeds.contains(row.getFeedId())) {
				checks.gap("SOURCE_LINK");
				item.getDataGaps().add("原实耗投料行未落在本次精确工单的投料范围内");
			}
			sources.add(item);
		}
		List<MesMaterialIntegrityDTO.Feed> balances = new ArrayList<>();
		for (var feed : feedRows) {
			var item = new MesMaterialIntegrityDTO.Feed().setFeedId(feed.getId().toString())
					.setArrangeCode(feed.getArrangeCode())
					.setMaterialCode(feed.getMatCode()).setBatchNo(feed.getMatBatch()).setUnit(feed.getMatUnit())
					.setFeedQuantity(text(feed.getFeedNum())).setRecordedUsedQuantity(text(feed.getUsedNum()))
					.setIssues(new ArrayList<>()).setDataGaps(new ArrayList<>());
			try {
				known(feed.getFeedNum() != null && feed.getUsedNum() != null && StringUtils.hasText(feed.getMatUnit()),
						"投料量、已用量或实际单位未完整保存");
				correct(feed.getFeedNum().signum() >= 0 && feed.getUsedNum().signum() >= 0
						&& feed.getUsedNum().compareTo(feed.getFeedNum()) <= 0, "当前投料已用量为负或超过投料量");
				known(!unprovenFeeds.contains(item.getFeedId()),
						"该投料有未核清的原实耗或恢复来源，未按当前已用量反推净耗");
				BigDecimal evidenced = netByFeed.getOrDefault(item.getFeedId(), BigDecimal.ZERO);
				item.setEvidencedNetQuantity(text(evidenced));
				correct(evidenced.compareTo(feed.getUsedNum()) == 0, "原耗料净额与投料行当前已用量未核平");
			} catch (Unproven problem) {
				checks.gap("FEED_BALANCE");
				item.getDataGaps().add(problem.getMessage());
			} catch (Deviation problem) {
				checks.bad("FEED_BALANCE");
				item.getIssues().add(problem.getMessage());
			}
			balances.add(item);
		}
		if (details.isEmpty()) {
			checks.gap("SOURCE_LINK");
			gaps.add("尚无可归属的原实耗，未把领料、投料或当前已用量当作实耗证据");
		}
		List<String> riskKeys = new ArrayList<>();
		sources.forEach(source -> source.getIssues()
				.forEach(issue -> riskKeys.add(source.getOriginal().getKey() + ':' + issue)));
		balances.forEach(
				feed -> feed.getIssues().forEach(issue -> riskKeys.add("FEED:" + feed.getFeedId() + ':' + issue)));
		boolean complete = checks.unknown == 0;
		sources.sort(Comparator.comparing(
						(MesMaterialIntegrityDTO.Source row) -> row.getIssues().isEmpty() && row.getDataGaps().isEmpty())
				.thenComparing(row -> row.getOriginal().getKey()));
		balances.sort(Comparator.comparing(
						(MesMaterialIntegrityDTO.Feed row) -> row.getIssues().isEmpty() && row.getDataGaps().isEmpty())
				.thenComparing(MesMaterialIntegrityDTO.Feed::getFeedId));
		return new MesMaterialIntegrityDTO.Evidence().setVersion(VERSION).setTenantId(tenant.toString())
				.setFactoryCode(factory)
				.setTaskCode(taskCode).setObservedAt(LocalDateTime.now()).setScopeComplete(complete)
				.setStatus(checks.bad > 0 ? "ANOMALY" : complete ? "CONSISTENT" : "DATA_GAP")
				.setSourceCount(details.size()).setFeedCount(feedRows.size()).setChecks(checks.list())
				.setSources(sources.stream().limit(50).toList()).setFeeds(balances.stream().limit(50).toList())
				.setDetailTruncated(sources.size() > 50 || balances.size() > 50).setDataGaps(gaps)
				.setRiskKeys(riskKeys.stream().sorted().toList())
				.setEvidenceRefs(List.of("MES:mes_task:" + taskCode, "MES:materialTrace:" + taskCode));
	}

	/**
	 * 原报告与原供料精确关联；库存消失不能用当前同批库存替代。
	 */
	private Boolean source(Detail detail) {
		Row row = detail.getRecord();
		known(detail.getOriginalRoot() != null && StringUtils.hasText(row.getRequestId()),
				"原耗料主账或永久请求关联未核清");
		known(row.getReportDomain() == null || Boolean.TRUE.equals(row.getReportLinked()), "原GOOD/NG报告精确关联缺失");
		known(row.getFeedId() == null || Boolean.TRUE.equals(row.getFeedLinked()), "原投料精确关联缺失");
		boolean stock =
				"GOOD_BACKFLUSH".equals(row.getSourceKind()) || "NG_POLICY_BACKFLUSH".equals(row.getSourceKind());
		String supply = stock ? "mes_warehouse_live_stock" : "mes_warehouse_feed";
		String sourceId = stock ? row.getStockId() : row.getFeedId();
		Fact actual = detail.getRelatedSources().stream().filter(fact -> supply.equals(fact.getTableName())
				&& Objects.equals(sourceId, fact.getSourceId())).findFirst().orElse(null);
		known(actual != null, "原投料或原库存主键来源不可读");
		known(StringUtils.hasText(row.getUnit()) && row.getUnit().equals(actual.getFields().get("mat_unit")),
				"原耗料实际单位与当前精确供料行不同或缺失，禁止跨单位对账");
		known(StringUtils.hasText(row.getProduceTaskCode()) && StringUtils.hasText(row.getArrangeCode()),
				"原工序或派工归属缺失");
		if ("NG_POLICY_BACKFLUSH".equals(row.getSourceKind())) {
			Fact allocation = rawPolicyLine(detail);
			Fact out = detail.getRelatedSources().stream()
					.filter(fact -> "mes_warehouse_inout".equals(fact.getTableName())
							&& Objects.equals(allocation.getFields().get("source_flow_id"), fact.getSourceId()))
					.findFirst().orElse(null);
			known(out != null && Objects.equals(row.getUnit(), out.getFields().get("mat_unit"))
							&& sameAmount(amount(row.getQuantity()), field(out, "operate_num"))
							&& sameAmount(field(allocation, "before_quantity"), field(out, "stock_before_num"))
							&& sameAmount(field(allocation, "after_quantity"), field(out, "stock_after_num")),
					"原NG库存分配缺少同源真实OUT数量、单位与扣减前后凭据");
		}
		return true;
	}

	/**
	 * 只用原量与冻结替代比例校验实际量/需求当量，不读当前MDM补比例。
	 */
	private BigDecimal quantity(Detail detail) {
		Row row = detail.getRecord();
		BigDecimal original = amount(row.getQuantity());
		known(original != null && StringUtils.hasText(row.getUnit()) &&
				StringUtils.hasText(row.getActualMaterialCode()) &&
				StringUtils.hasText(row.getRequirementMaterialCode()), "原实际量、单位或需求物料缺失");
		correct(original.signum() > 0, "原实耗数量不是正量");
		Fact raw = detail.getRelatedSources().stream().filter(fact -> row.getSourceTable().equals(fact.getTableName())
				&& row.getSourceId().equals(fact.getSourceId())).findFirst().orElse(null);
		known(raw != null, "原耗料行不可读");
		BigDecimal ratio = field(raw, "substitute_ratio"), requirement = amount(row.getRequirementQuantity());
		known(ratio != null && ratio.signum() > 0 && requirement != null,
				"原替代比例或需求当量缺失，未按现行物料资料补算");
		correct(requirement.multiply(ratio).compareTo(original) == 0, "原实际耗量与原需求当量、替代比例不符");
		String flag = raw.getFields().get("substitute_flag");
		known(Set.of("true", "false", "t", "f", "1", "0").contains(Objects.toString(flag, "")),
				"原替代标记缺失或未识别");
		boolean substitute = "true".equals(flag) || "t".equals(flag) || "1".equals(flag);
		correct(substitute || row.getActualMaterialCode().equals(row.getRequirementMaterialCode()) &&
						ratio.compareTo(BigDecimal.ONE) == 0,
				"主料标记与原需求物料或原比例不一致");
		return original;
	}

	/**
	 * 组合总耗仅计一次；GOOD/NG份额必须分域且总份额等于原总耗。
	 */
	private Boolean shares(Detail detail, Set<String> verifiedBatches) {
		Row row = detail.getRecord();
		if (!"COMBINED_FEED".equals(row.getSourceKind())) {
			return true;
		}
		verifyBatch(detail, verifiedBatches);
		known(!detail.getAttributions().isEmpty(), "原组合耗料缺少真实产出份额");
		Set<String> outputKeys = new HashSet<>();
		Set<String> shareIds = new HashSet<>();
		BigDecimal total = BigDecimal.ZERO;
		for (Fact share : detail.getAttributions()) {
			Map<String, String> facts = share.getFields();
			String kind = facts.get("output_kind");
			known(Set.of("GOOD", "NG").contains(Objects.toString(kind, "")) &&
							StringUtils.hasText(facts.get("share_id"))
							&& shareIds.add(facts.get("share_id")) && StringUtils.hasText(facts.get("output_key"))
							&& outputKeys.add(facts.get("output_key")) && StringUtils.hasText(facts.get("source_birth_id")),
					"组合产出域、份额身份或首次来源凭据不完整");
			known("GOOD".equals(kind) ? facts.get("good_report_id") != null && facts.get("ng_report_id") == null
							: facts.get("ng_report_id") != null && facts.get("good_report_id") == null,
					"组合GOOD/NG产出主键域混用");
			BigDecimal quantity = amount(facts.get("share_quantity"));
			known(quantity != null, "组合原份额数量缺失");
			correct(quantity.signum() > 0, "组合原份额不是正量");
			total = total.add(quantity);
		}
		BigDecimal original = amount(row.getQuantity());
		known(original != null, "组合原总耗量缺失");
		correct(total.compareTo(original) == 0, "组合原产出份额合计与原总耗不等额");
		return true;
	}

	/**
	 * 组合原SHA与产出/耗用/份额两向全集由原领域算法核验，不只检查行数和合计。
	 */
	private void verifyBatch(Detail detail, Set<String> verifiedBatches) {
		Row row = detail.getRecord();
		if (verifiedBatches.contains(row.getRootId())) {
			return;
		}
		try {
			var batch = batches.selectById(Long.valueOf(row.getRootId()));
			known(batch != null && detail.getFactoryCode().equals(batch.getFactoryCode())
							&& detail.getTenantId().equals(Objects.toString(batch.getTenantId(), null))
							&& row.getTaskCode().equals(batch.getTaskCode()) &&
							row.getArrangeCode().equals(batch.getArrangeCode())
							&& row.getProduceTaskCode().equals(batch.getProduceTaskCode()) &&
							row.getRequestId().equals(batch.getRequestId()),
					"原组合总耗账身份与当前完整范围不符");
			var outputs = batchOutputs.permanentRows(batch.getTenantId(), batch.getFactoryCode(), batch.getId());
			var consumptions = batchConsumptions.permanentRows(batch.getTenantId(), batch.getFactoryCode(),
					batch.getId());
			var shares = batchShares.permanentRows(batch.getTenantId(), batch.getFactoryCode(), batch.getId());
			known((long) outputs.size() + consumptions.size() + shares.size() <= 10000, "组合原账全集超过完整核验上限");
			batchFacts.verify(batch, outputs, consumptions, shares);
			verifiedBatches.add(row.getRootId());
		} catch (Unproven missing) {
			throw missing;
		} catch (RuntimeException invalid) {
			throw new Unproven("原组合总耗账版本、SHA或真实产出/耗用/份额全集未核清");
		}
	}

	/**
	 * 逐原来源识别恢复。没有原凭据时不从当前余额或其他行的恢复量推算。
	 */
	private BigDecimal restored(Detail detail, List<Detail> all, BigDecimal original,
	                            Map<String, PolicyEvidence> verifiedPolicies) {
		Row row = detail.getRecord();
		known(detail.getOriginalRoot() != null, "原耗料主账不可读，恢复量不能核定");
		if (row.getSourceKind().startsWith("GOOD_")) {
			return goodRestored(detail, original);
		}
		known("MATERIAL_RECORDED".equals(row.getSourceStatus()), "原独立耗料状态未识别");
		if (policyKind(row.getSourceKind())) {
			PolicyEvidence policy = verifiedPolicies.computeIfAbsent(row.getRootId(),
					ignored -> verifyPolicy(detail, all));
			return policy.restoredByAllocation().get(row.getSourceId());
		}
		if (detail.getRestores().isEmpty()) {
			known("NONE_RECORDED".equals(row.getRestorationStatus()), "原耗料已标识恢复但缺少逐源凭据");
			return BigDecimal.ZERO;
		}
		if ("NG_FEED".equals(row.getSourceKind())) {
			return ngRestored(detail, all, original);
		}
		BigDecimal total = BigDecimal.ZERO;
		for (Fact restore : detail.getRestores()) {
			known(row.getSourceId().equals(restore.getFields().get("consumption_id"))
							&& row.getFeedId().equals(restore.getFields().get("feed_id"))
							&& row.getRootId().equals(restore.getFields().get("batch_id"))
							&& row.getUnit().equals(restore.getFields().get("mat_unit"))
							&& row.getActualMaterialCode().equals(restore.getFields().get("mat_code")),
					"组合恢复未精确指向原总耗、原投料或实际物料单位");
			approvedRestore(detail, restore, "batch_id", row.getRootId());
			BigDecimal amount = field(restore, "restored_num");
			known(amount != null, "组合恢复数量缺失");
			correct(amount.signum() > 0, "组合恢复数量不是正量");
			BigDecimal before = field(restore, "used_before_num"), after = field(restore, "used_after_num");
			known(before != null && after != null, "组合恢复前后已用量缺失");
			correct(before.subtract(after).compareTo(amount) == 0, "组合恢复前后量与恢复凭据不符");
			total = total.add(amount);
		}
		correct(total.compareTo(original) <= 0, "组合累计恢复超过原耗料量");
		return total;
	}

	/**
	 * 全operation的SHA、原分配及恢复两向全集一次核验，不按某一行的状态猜整笔已恢复。
	 */
	private PolicyEvidence verifyPolicy(Detail detail, List<Detail> all) {
		Row row = detail.getRecord();
		try {
			List<Detail> group = all.stream().filter(other -> policyKind(other.getRecord().getSourceKind())
					&& Objects.equals(row.getRootId(), other.getRecord().getRootId())).toList();
			Header header = typed(detail.getOriginalRoot(), Header.class);
			known(Objects.equals(detail.getTenantId(), Objects.toString(header.getTenantId(), null))
							&& Objects.equals(detail.getFactoryCode(), header.getFactoryCode())
							&& Objects.equals(row.getRootId(), Objects.toString(header.getId(), null))
							&& Objects.equals(row.getTaskCode(), header.getTaskCode())
							&& Objects.equals(row.getProduceTaskCode(), header.getProduceTaskCode())
							&& Objects.equals(row.getArrangeCode(), header.getArrangeCode())
							&& Objects.equals(row.getRequestId(), header.getRequestId()),
					"NG逐料原主账身份与当前工单范围不一致");
			List<Line> allocations = new ArrayList<>();
			Map<String, Fact> reversals = new LinkedHashMap<>();
			Map<String, Fact> restores = new LinkedHashMap<>();
			for (Detail member : group) {
				known(member.getOriginalRoot() != null
								&& Objects.equals(member.getOriginalRoot().getFields(), detail.getOriginalRoot().getFields()),
						"NG同operation出现不一致原主账");
				allocations.add(typed(rawPolicyLine(member), Line.class));
				member.getReversals().forEach(fact -> uniqueFact(reversals, fact));
				member.getRestores().forEach(fact -> uniqueFact(restores, fact));
			}
			POLICY_CODEC.verify(header, allocations);
			Map<String, BigDecimal> restored = new HashMap<>();
			allocations.forEach(line -> restored.put(line.getId().toString(), BigDecimal.ZERO));
			if (reversals.isEmpty()) {
				known(restores.isEmpty() && group.stream()
								.allMatch(member -> "NONE_RECORDED".equals(member.getRecord().getRestorationStatus())),
						"NG逐料恢复缺少原主凭据");
			} else {
				known(reversals.size() == 1, "NG逐料原恢复主凭据不唯一");
				List<Restore> rows = restores.values().stream().map(fact -> typed(fact, Restore.class)).toList();
				POLICY_CODEC.verifyRestore(typed(reversals.values().iterator().next(), Reversal.class), rows, header,
						allocations);
				for (Restore restore : rows) {
					if (!"STOCK".equals(restore.getSourceKind())) {
						continue;
					}
					Fact in = group.stream().filter(member -> Objects.equals(member.getRecord().getSourceId(),
									restore.getAllocationId().toString()))
							.flatMap(member -> member.getRelatedSources().stream())
							.filter(fact -> "mes_warehouse_inout".equals(fact.getTableName())
									&& Objects.equals(fact.getSourceId(), restore.getRestoreFlowId().toString()))
							.findFirst().orElse(null);
					known(in != null && Objects.equals(in.getFields().get("mat_unit"), restore.getMatUnit())
									&& sameAmount(restore.getQuantity(), field(in, "operate_num"))
									&& sameAmount(restore.getBeforeQuantity(), field(in, "stock_before_num"))
									&& sameAmount(restore.getAfterQuantity(), field(in, "stock_after_num")),
							"NG库存恢复缺少精确原stockId的真实IN数量、单位与恢复前后凭据");
				}
				rows.forEach(restore -> restored.put(restore.getAllocationId().toString(), restore.getQuantity()));
			}
			return new PolicyEvidence(Map.copyOf(restored));
		} catch (Unproven missing) {
			throw missing;
		} catch (RuntimeException invalid) {
			throw new Unproven("NG逐料原SHA、分配或恢复完整集合未核清");
		}
	}

	private boolean policyKind(String kind) {
		return "NG_POLICY_FEED".equals(kind) || "NG_POLICY_BACKFLUSH".equals(kind);
	}

	private Fact rawPolicyLine(Detail detail) {
		return detail.getRelatedSources().stream()
				.filter(fact -> "mes_ng_material_policy_allocation".equals(fact.getTableName())
						&& Objects.equals(detail.getRecord().getSourceId(), fact.getSourceId())).findFirst()
				.orElseThrow(() -> new Unproven("NG逐料原分配不可读"));
	}

	private <T> T typed(Fact fact, Class<T> type) {
		known(fact != null && fact.getFields() != null &&
						Objects.equals(fact.getSourceId(), fact.getFields().get("id")),
				"NG逐料原账主键不可读");
		return POLICY_JSON.convertValue(fact.getFields(), type);
	}

	private void uniqueFact(Map<String, Fact> target, Fact fact) {
		Fact previous = target.putIfAbsent(fact.getSourceId(), fact);
		known(previous == null || Objects.equals(previous, fact), "同一NG原凭据主键出现不同内容");
	}

	private record PolicyEvidence(Map<String, BigDecimal> restoredByAllocation) {
	}

	/**
	 * GOOD现代纠正按原source串联累计分配；旧整笔恢复无法唯一归属时保持缺证。
	 */
	private BigDecimal goodRestored(Detail detail, BigDecimal original) {
		Row row = detail.getRecord();
		known(Set.of("EFFECTIVE", "REVERSED").contains(Objects.toString(row.getSourceStatus(), "")),
				"原GOOD耗料状态未完整保存");
		String rootStatus = detail.getOriginalRoot().getFields().get("status");
		known(Set.of("EFFECTIVE", "REVERSED").contains(Objects.toString(rootStatus, "")), "原GOOD报告状态未完整保存");
		if (detail.getRestores().isEmpty()) {
			known("NONE_RECORDED".equals(row.getRestorationStatus()) && "EFFECTIVE".equals(rootStatus)
					&& "EFFECTIVE".equals(row.getSourceStatus()), "原GOOD恢复缺少可唯一分配的纠正凭据");
			BigDecimal corrected = field(detail.getOriginalRoot(), "corrected_report_num");
			known(corrected != null && corrected.signum() == 0, "原报告已纠正或纠正量未知，但没有本源材料纠正凭据");
			return BigDecimal.ZERO;
		}
		known(detail.getRestores().stream()
						.allMatch(fact -> "mes_report_quantity_correction_line".equals(fact.getTableName())),
				"旧整笔恢复凭据尚不能逐源核定净耗，请回原实耗工作台核查");
		List<Fact> lines = new ArrayList<>(detail.getRestores());
		known(lines.stream().allMatch(fact -> field(fact, "corrected_before_quantity") != null), "原纠正累计量缺失");
		lines.sort(Comparator.comparing(fact -> field(fact, "corrected_before_quantity")));
		BigDecimal total = BigDecimal.ZERO;
		for (Fact line : lines) {
			Map<String, String> facts = line.getFields();
			known(row.getSourceId().equals(facts.get("source_id")) && row.getReportId().equals(facts.get("report_id"))
							&& ("GOOD_FEED".equals(row.getSourceKind()) ? "MATERIAL_CONSUME" : "MATERIAL_FLOW").equals(
							facts.get("allocation_type")),
					"原材料纠正指向了其他报工或原来源");
			known(detail.getReversals().stream()
							.anyMatch(reverse -> Objects.equals(reverse.getSourceId(), facts.get("reverse_request_id"))
									&& "APPROVED".equals(reverse.getFields().get("status"))),
					"材料纠正缺少原批准并执行的申请凭据");
			BigDecimal amount = field(line, "correction_quantity"), base = field(line,
					"original_quantity"), remaining = field(line, "remaining_quantity");
			known(amount != null && base != null && remaining != null, "材料纠正原量、本次量或剩余量缺失");
			correct(amount.signum() > 0 && base.compareTo(original) == 0 &&
							field(line, "corrected_before_quantity").compareTo(total) == 0
							&& original.subtract(total.add(amount)).compareTo(remaining) == 0 && remaining.signum() >= 0,
					"材料纠正累计链或原量、剩余量不守恒");
			total = total.add(amount);
		}
		correct(!("REVERSED".equals(rootStatus) || "REVERSED".equals(row.getSourceStatus())) ||
				total.compareTo(original) == 0, "已冲销GOOD仍残留未恢复原耗料量");
		return total;
	}

	/**
	 * NG恢复是同operation/feed的完整allocation集合，不给每行重复扣整份恢复量。
	 */
	private BigDecimal ngRestored(Detail detail, List<Detail> all, BigDecimal original) {
		Row row = detail.getRecord();
		known(detail.getRestores().size() == 1, "原NG同投料恢复凭据不唯一");
		Fact restore = detail.getRestores().get(0);
		known(row.getRootId().equals(restore.getFields().get("operation_id")) &&
						row.getFeedId().equals(restore.getFields().get("feed_id")),
				"原NG恢复未对应原operation和投料");
		List<Row> group = all.stream().map(Detail::getRecord).filter(other -> "NG_FEED".equals(other.getSourceKind())
				&& Objects.equals(other.getRootId(), row.getRootId()) &&
				Objects.equals(other.getFeedId(), row.getFeedId())).toList();
		List<String> restoredIds;
		try {
			restoredIds = JSON.readValue(restore.getFields().get("allocation_ids"), new TypeReference<List<String>>() {
			});
		} catch (Exception invalid) {
			throw new Unproven("原NG恢复allocation集合无法解析");
		}
		Set<String> expected = new HashSet<>(group.stream().map(Row::getSourceId).toList());
		known(restoredIds != null && restoredIds.size() == new HashSet<>(restoredIds).size()
				&& expected.equals(new HashSet<>(restoredIds)), "原NG恢复未完整且唯一覆盖同源allocation集合");
		BigDecimal total = BigDecimal.ZERO;
		for (Row other : group) {
			BigDecimal quantity = amount(other.getQuantity());
			known(quantity != null && Objects.equals(other.getUnit(), row.getUnit()), "原NG恢复分配单位或原数量不完整");
			total = total.add(quantity);
		}
		BigDecimal restored = field(restore, "restored_num");
		known(restored != null, "原NG恢复数量缺失");
		correct(total.compareTo(restored) == 0, "原NG完整集合总量与恢复量不等额");
		known(row.getUnit().equals(restore.getFields().get("mat_unit")) &&
						row.getActualMaterialCode().equals(restore.getFields().get("mat_code")),
				"原NG恢复实际物料或单位与原耗用不同");
		BigDecimal before = field(restore, "used_before_num"), after = field(restore, "used_after_num");
		known(before != null && after != null, "原NG恢复前后已用量缺失");
		correct(before.subtract(after).compareTo(restored) == 0, "原NG恢复前后量与恢复凭据不符");
		approvedRestore(detail, restore, "operation_id", row.getRootId());
		return original;
	}

	private void approvedRestore(Detail detail, Fact restore, String rootField, String rootId) {
		known(detail.getReversals().stream()
						.anyMatch(reverse -> Objects.equals(reverse.getSourceId(), restore.getFields().get("reversal_id"))
								&& "MATERIAL_RESTORED".equals(reverse.getFields().get("status")) &&
								rootId.equals(reverse.getFields().get(rootField))),
				"原恢复缺少同源已入账主凭据");
	}

	private <T> T validate(Checks checks, String code, MesMaterialIntegrityDTO.Source item, Supplier<T> work) {
		try {
			return work.get();
		} catch (Unproven gap) {
			checks.gap(code);
			item.getDataGaps().add(gap.getMessage());
		} catch (Deviation deviation) {
			checks.bad(code);
			item.getIssues().add(deviation.getMessage());
		}
		return null;
	}

	private BigDecimal amount(String value) {
		try {
			return value == null ? null : new BigDecimal(value);
		} catch (NumberFormatException invalid) {
			return null;
		}
	}

	private boolean sameAmount(BigDecimal left, BigDecimal right) {
		return left != null && right != null && left.compareTo(right) == 0;
	}

	private BigDecimal field(Fact fact, String name) {
		return fact == null ? null : amount(fact.getFields().get(name));
	}

	private String text(BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros().toPlainString();
	}

	private void known(boolean condition, String message) {
		if (!condition) {
			throw new Unproven(message);
		}
	}

	private void correct(boolean condition, String message) {
		if (!condition) {
			throw new Deviation(message);
		}
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	private static final class Unproven extends RuntimeException {
		private Unproven(String message) {
			super(message);
		}
	}

	private static final class Deviation extends RuntimeException {
		private Deviation(String message) {
			super(message);
		}
	}

	/**
	 * 检查计数独立保留偏差与缺证，不能以已知异常遮盖同项未知。
	 */
	private static final class Checks {
		private static final Map<String, String> NAMES = new LinkedHashMap<>();

		static {
			NAMES.put("SOURCE_LINK", "原耗料与供料来源");
			NAMES.put("MATERIAL_QUANTITY", "原量与替代比例");
			NAMES.put("RESTORE_PROOF", "纠正与恢复净额");
			NAMES.put("OUTPUT_SHARE", "组合产出分摊");
			NAMES.put("FEED_BALANCE", "逐投料已用量对账");
		}

		private final Map<String, Integer> badCounts = new HashMap<>();
		private final Map<String, Integer> unknownCounts = new HashMap<>();
		private int bad;
		private int unknown;

		private void bad(String code) {
			bad++;
			badCounts.merge(code, 1, Integer::sum);
		}

		private void gap(String code) {
			unknown++;
			unknownCounts.merge(code, 1, Integer::sum);
		}

		private List<MesReportQuantityConsistencyDTO.Check> list() {
			return NAMES.entrySet().stream().map(entry -> {
				int problems = badCounts.getOrDefault(entry.getKey(), 0), gaps = unknownCounts.getOrDefault(
						entry.getKey(), 0);
				return new MesReportQuantityConsistencyDTO.Check().setCode(entry.getKey()).setName(entry.getValue())
						.setState(problems > 0 ? "ANOMALY" : gaps > 0 ? "UNKNOWN" : "PASS")
						.setSummary("已确认偏差" + problems + "项，待补证" + gaps + "项");
			}).toList();
		}
	}
}
