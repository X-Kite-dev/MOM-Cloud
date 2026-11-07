package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.dto.ProcessProcedureDTO;
import com.kitex.mom.mes.util.MesMaterialSubstituteResolver;
import com.kitex.mom.mes.util.MesTaskUtils;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

import com.kitex.mom.mes.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


/**
 * 原GOOD回执内的逐料分配原件；仅读本事务已成功的实际耗用，绝不再次扣料。
 */
@Service
@RequiredArgsConstructor
public class MesGoodMaterialPolicyReceiptCollector {
	/**
	 * 原GOOD逐料分配原件的固定算法域版本。
	 */
	public static final String VERSION = "MES_GOOD_MATERIAL_POLICY_RECEIPT_V1";
	private final MesActualFactoryAccessService access;
	private final MesExecutionTaskLock locks;
	private final MesTaskArrangeMapper arrangements;
	private final MesTaskMapper tasks;
	private final MesProductionFeedBatchMapper births;
	private final MesWarehouseFeedMapper feeds;
	private final MesReportMaterialConsumeMapper consumptions;
	private final MesWarehouseInoutMapper flows;
	private final MesMaterialConsumptionModeResolver modes;
	private final MesPhysicalTransformCodec codec;
	private final MesGoodMaterialPolicyReceiptMapper nativeOrigins;
	private final MesWarehouseFeedBirthObservationMapper feedBirthObservations;

	/**
	 * 在原GOOD事务内冻结全部实际分配，不再次扣料或按今日主数据补历史。
	 *
	 * @param report 已保存、属于当前实际组织的原合格报告
	 * @return 版本/完整原文/版本域SHA；旧统一规则及正式回流返回null并保留旧结果字节
	 * @throws com.kitex.common.core.exception.CheckedException 出生、原单位、原开工规则或分配全集不完整时拒绝并回滚调用事务
	 */
	@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
	public Map<String, String> capture(MesTaskReportEntity report) {
		String factory = access.require("write");
		Long tenant = TenantContextHolder.getTenantId();
		require(report != null && report.getId() != null && report.getId() > 0
						&& Objects.equals(report.getTenantId(), tenant) && Objects.equals(report.getFactoryCode(), factory),
				"逐料回执缺少当前组织的真实原GOOD报告");
		if ("ROUTE_RETURN".equals(report.getExecutionKind())) {
			return null;
		}
		var owner = locks.arrange(report.getArrangeCode());
		var arrangeRows = arrangements.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
				.eq(MesTaskArrangeEntity::getTenantId, tenant).eq(MesTaskArrangeEntity::getFactoryCode, factory)
				.eq(MesTaskArrangeEntity::getCode, report.getArrangeCode()).last("LIMIT 2 FOR UPDATE NOWAIT"));
		require(arrangeRows != null && arrangeRows.size() == 1, "逐料回执缺少唯一原派工");
		var arrange = arrangeRows.get(0);
		locks.assertArrange(owner, arrange);
		var selection = modes.selection(arrange);
		if (!selection.perMaterial()) {
			return null;
		}
		require(Boolean.TRUE.equals(nativeOrigins.ready()), "逐料GOOD原件的数据库即时及提交守卫尚未完整安装");
		require("START_CHECK".equals(selection.source()) && arrange.getMaterialGateRecordId() != null
						&& arrange.getMaterialGateRecordId().toString().equals(selection.sourceId())
						&& Objects.equals(arrange.getSourceRouteNodeCode(), selection.routeNodeCode()),
				"逐料原GOOD须已有该派工真实原开工规则，不能在报工后补定模式");
		var taskRows = tasks.selectList(new LambdaQueryWrapper<MesTaskEntity>()
				.eq(MesTaskEntity::getTenantId, tenant).eq(MesTaskEntity::getFactoryCode, factory)
				.eq(MesTaskEntity::getCode, arrange.getTaskCode()).last("LIMIT 2 FOR UPDATE NOWAIT"));
		require(taskRows != null && taskRows.size() == 1, "逐料回执缺少唯一原父工单");
		var task = taskRows.get(0);
		require(Objects.equals(task.getTenantId(), tenant) && Objects.equals(task.getFactoryCode(), factory)
						&& Objects.equals(task.getCode(), arrange.getTaskCode()) &&
						Objects.equals(report.getTaskCode(), task.getCode())
						&& Objects.equals(arrange.getTenantId(), tenant) && Objects.equals(arrange.getFactoryCode(), factory)
						&& Objects.equals(report.getProduceTaskCode(), arrange.getProduceTaskCode()),
				"逐料回执原执行归属不一致");
		var birth = births.actualBirth(tenant, factory, "GOOD", report.getId());
		require(birth != null && birth.getId() != null && birth.getId() > 0 &&
						Boolean.TRUE.equals(birth.getSameTransaction())
						&& Objects.equals(birth.getSourceId(), report.getId()) && "GOOD".equals(birth.getOutputKind())
						&& Objects.equals(birth.getTenantId(), tenant) && Objects.equals(birth.getFactoryCode(), factory),
				"逐料回执只能记录本事务首次真实GOOD，禁止追认旧来源或借用NG");
		var source = codec.objectDocument(birth.getSourceSnapshot(), 32 * 1024 * 1024);
		require(report.getId().toString().equals(source.path("id").asText())
				&& tenant.toString().equals(source.path("tenant_id").asText()) &&
				factory.equals(source.path("factory_code").asText())
				&& Objects.equals(report.getTaskCode(), source.path("task_code").asText())
				&& Objects.equals(report.getProduceTaskCode(), source.path("produce_task_code").asText())
				&& Objects.equals(report.getArrangeCode(), source.path("arrange_code").asText())
				&& Objects.equals(report.getRequestId(), source.path("request_id").asText())
				&& Objects.equals(report.getReportUnit(), source.path("report_unit").asText())
				&& "EFFECTIVE".equals(source.path("status").asText()) && "0".equals(source.path("del_flag").asText())
				&& "EFFECTIVE".equals(report.getStatus()), "逐料回执原报告出生身份或原计量不一致");
		BigDecimal processed = codec.quantity(birth.getQuantity(), false, "原GOOD加工量");
		require(equal(processed, report.getReportNum())
						&& equal(processed, codec.quantity(birth.getEffectiveQuantity(), false, "原GOOD有效量"))
						&& codec.quantity(birth.getCorrectedQuantity(), true, "原GOOD已纠正量").signum() == 0,
				"逐料回执只能冻结本次未纠正的原GOOD量");
		codec.code(report.getReportUnit(), "原生产单位", 100);
		var rows = MesTaskUtils.getCurrentProcess(task.getProcessRouteSnapshot(), arrange.getRouteCode(),
				arrange.getSourceRouteNodeCode()).getMdmProcessProcedureMatList();
		require(rows != null && !rows.isEmpty() && rows.size() <= 100, "逐料回执缺完整原节点需求");
		var demand = new TreeMap<String, ProcessProcedureDTO.ProcessProcedureMatDTO>();
		for (var row : rows) {
			require(row != null, "原节点需求缺失");
			codec.code(row.getMaterialCode(), "原需求物料", 100);
			require(demand.put(row.getMaterialCode(), row) == null, "原节点需求重复");
			codec.code(row.getUnit(), "原需求单位", 100);
			amount(row.getQuantity(), false, "原需求单耗");
			require(selection.forRequirement(row.getMaterialCode()).name().equals(row.getConsumptionMode()),
					"逐料回执需求偏离原开工方式");
		}
		require(demand.keySet().equals(selection.requirementModes().keySet()), "逐料回执需求集合不等于原开工完整规则");
		var actualFeed = consumptions.selectList(new LambdaQueryWrapper<MesReportMaterialConsumeEntity>()
				.eq(MesReportMaterialConsumeEntity::getTenantId, tenant)
				.eq(MesReportMaterialConsumeEntity::getFactoryCode, factory)
				.eq(MesReportMaterialConsumeEntity::getReportId, report.getId()).last("LIMIT 5001"));
		var actualStock = flows.selectList(new LambdaQueryWrapper<MesWarehouseInoutEntity>()
				.eq(MesWarehouseInoutEntity::getTenantId, tenant).eq(MesWarehouseInoutEntity::getFactoryCode, factory)
				.eq(MesWarehouseInoutEntity::getBillId, report.getId())
				.eq(MesWarehouseInoutEntity::getBizType, "back_flush_out")
				.eq(MesWarehouseInoutEntity::getOperateType, "out").last("LIMIT 5001"));
		require(actualFeed != null && actualStock != null && (long) actualFeed.size() + actualStock.size() <= 5000,
				"原GOOD实际分配不可读取或超过5000行，不能截断证明");
		var feedIds = new HashSet<Long>();
		for (var row : actualFeed) {
			require(row != null && row.getFeedId() != null && row.getFeedId() > 0, "实际投料分配缺少原来源主键");
			feedIds.add(row.getFeedId());
		}
		var feedSources = new HashMap<Long, MesWarehouseFeedEntity>();
		if (!feedIds.isEmpty()) {
			var sourceRows = feeds.selectList(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
					.eq(MesWarehouseFeedEntity::getTenantId, tenant).eq(MesWarehouseFeedEntity::getFactoryCode, factory)
					.in(MesWarehouseFeedEntity::getId, feedIds).last("LIMIT 5001"));
			require(sourceRows != null && sourceRows.size() == feedIds.size(), "原投料来源全集缺失或重复，不能截断证明");
			for (var feed : sourceRows) {
				require(feed != null && feed.getId() != null && feedIds.contains(feed.getId())
						&& Objects.equals(feed.getTenantId(), tenant) && Objects.equals(feed.getFactoryCode(), factory)
						&& feedSources.put(feed.getId(), feed) == null, "原投料来源重复或属于其他组织");
			}
			require(feedSources.keySet().equals(feedIds), "原投料来源全集不一致");
			var observations = feedBirthObservations.batchObservations(tenant, factory, feedIds);
			require(observations != null && observations.size() == feedIds.size(), "原投料出生观察全集缺失或重复");
			var seenOrigins = new HashSet<Long>();
			for (var observation : observations) {
				require(observation != null && observation.getFeedId() != null &&
						seenOrigins.add(observation.getFeedId())
						&& feedIds.contains(observation.getFeedId()), "原投料出生观察引用重复或不属于实际分配");
				var feed = feedSources.get(observation.getFeedId());
				require("MES_FEED_BIRTH_V1".equals(observation.getNativeBirthVersion())
								&& observation.getHeaderId() != null && observation.getHeaderId() > 0
								&& "MES_FEED_BIRTH_V1".equals(observation.getHeaderVersion())
								&& "RECORDED".equals(observation.getHeaderStatus())
								&& Boolean.TRUE.equals(observation.getHeaderHashValid())
								&& observation.getSourceCount() != null && observation.getSourceCount() > 0
								&& observation.getSourceCount() <= 5000
								&& Objects.equals(observation.getTaskId(), task.getId())
								&& Objects.equals(observation.getArrangeId(), arrange.getId())
								&& equal(observation.getHeaderQuantity(), feed.getFeedNum())
								&& Objects.equals(observation.getHeaderUnit(), feed.getMatUnit()),
						"新逐料回执须真实原投料出生、完整来源及同工单派工原单位，不能追认历史投料");
			}
		}
		var checkedBirths = new HashSet<Long>();
		var allocations = new ArrayList<Map<String, Object>>();
		for (var row : actualFeed) {
			require(row != null && Objects.equals(row.getTenantId(), tenant) &&
							Objects.equals(row.getFactoryCode(), factory)
							&& Objects.equals(row.getReportId(), report.getId()) &&
							Objects.equals(row.getArrangeCode(), report.getArrangeCode())
							&& Objects.equals(row.getReportRequestId(), report.getRequestId()) &&
							"EFFECTIVE".equals(row.getStatus()),
					"投料实际分配不是本次原GOOD份额");
			var feed = feedSources.get(row.getFeedId());
			require(feed != null && Objects.equals(feed.getTenantId(), tenant) &&
							Objects.equals(feed.getFactoryCode(), factory)
							&& Objects.equals(feed.getTaskCode(), task.getCode()) &&
							Objects.equals(feed.getArrangeCode(), arrange.getCode())
							&& Objects.equals(feed.getProduceTaskCode(), arrange.getProduceTaskCode())
							&& Objects.equals(feed.getMatCode(), row.getMatCode())
							&& Objects.equals(feed.getRequirementMatCode(), row.getRequirementMatCode())
							&& Objects.equals(feed.getSubstituteFlag(), row.getSubstituteFlag())
							&& equal(feed.getSubstituteRatio(), row.getSubstituteRatio()),
					"投料原来源身份缺失或与原GOOD不一致");
			if (checkedBirths.add(feed.getId())) {
				births.assertFeedBirth(feed.getId());
			}
			allocations.add(allocation("FEED", row.getId(), feed.getId(), row.getRequirementMatCode(), row.getMatCode(),
					feed.getMatUnit(), row.getConsumeNum(), row.getRequirementNum(), row.getSubstituteRatio(),
					row.getSubstituteFlag(),
					demand, selection, task.getBomSnapshot()));
		}
		for (var row : actualStock) {
			require(row != null && Objects.equals(row.getTenantId(), tenant) &&
							Objects.equals(row.getFactoryCode(), factory)
							&& Objects.equals(row.getBillId(), report.getId()) &&
							Objects.equals(row.getBillNo(), arrange.getCode())
							&& Objects.equals(row.getResourceNo(), report.getRequestId()) &&
							"back_flush_out".equals(row.getBizType())
							&& "out".equals(row.getOperateType()) && "STOCK_LINE_V1".equals(row.getStockProofVersion()),
					"倒冲实际分配不是本次原GOOD真实OUT");
			allocations.add(allocation("STOCK", row.getId(), row.getSourceStockId(), row.getRequirementMatCode(),
					row.getMatCode(),
					row.getMatUnit(), row.getOperateNum(), null, row.getSubstituteRatio(), row.getSubstituteFlag(),
					demand, selection, task.getBomSnapshot()));
		}
		var ids = new HashSet<String>();
		for (var allocation : allocations) {
			require(ids.add(allocation.get("kind") + ":" + allocation.get("sourceRowId")), "原GOOD真实分配引用重复");
		}
		for (var entry : demand.entrySet()) {
			var matching = allocations.stream().filter(row -> entry.getKey().equals(row.get("requirementCode")))
					.toList();
			BigDecimal total = matching.stream().map(row -> new BigDecimal((String) row.get("requirementQuantity")))
					.reduce(BigDecimal.ZERO, BigDecimal::add);
			require(equal(total, processed.multiply(entry.getValue().getQuantity())),
					"原GOOD逐需求完整分配不守恒：" + entry.getKey());
			if (!MesMaterialSubstituteResolver.resolvePolicy(task.getBomSnapshot(), entry.getKey()).allowMixed()) {
				require(matching.stream().map(row -> row.get("materialCode")).distinct().count() == 1,
						"原GOOD需求禁止混用多个实际物料");
			}
		}
		allocations.sort(Comparator.comparing(row -> row.get("kind") + ":" + row.get("sourceRowId")));
		var document = new LinkedHashMap<String, Object>();
		document.put("version", VERSION);
		document.put("reportId", report.getId().toString());
		document.put("sourceBirthId", birth.getId().toString());
		document.put("tenantId", tenant.toString());
		document.put("factoryCode", factory);
		document.put("requestId", report.getRequestId());
		document.put("taskCode", task.getCode());
		document.put("produceTaskCode", arrange.getProduceTaskCode());
		document.put("arrangeCode", arrange.getCode());
		document.put("routeNodeCode", arrange.getSourceRouteNodeCode());
		document.put("gateRecordId", arrange.getMaterialGateRecordId().toString());
		document.put("policyFingerprint", selection.sourceFingerprint());
		document.put("routeFingerprint", digest(task.getProcessRouteSnapshot()));
		document.put("bomFingerprint", digest(task.getBomSnapshot()));
		document.put("processedQuantity", codec.amount(processed));
		document.put("productionUnit", report.getReportUnit());
		document.put("requirements", demand.values().stream().map(row -> Map.of("materialCode", row.getMaterialCode(),
				"unit", row.getUnit(), "perUnit", codec.amount(row.getQuantity()), "mode",
				selection.forRequirement(row.getMaterialCode()).name())).toList());
		document.put("allocations", allocations);
		String snapshot = codec.snapshot(document);
		codec.objectDocument(snapshot, 16 * 1024 * 1024);
		return Map.of("version", VERSION, "snapshot", snapshot, "fingerprint", codec.fingerprint(VERSION, snapshot));
	}

	/**
	 * 将一条真实耗用引用转换为原需求当量，按冻结BOM核原主替比例及单位。
	 * 除法必须精确；不能以舍入、默认比例或未知单位形成成功回执。
	 */
	private Map<String, Object> allocation(String kind, Long rowId, Long sourceId, String required, String material,
	                                       String unit, BigDecimal quantity, BigDecimal declaredRequired,
	                                       BigDecimal ratio, Boolean substitute,
	                                       Map<String, ProcessProcedureDTO.ProcessProcedureMatDTO> demand,
	                                       MesMaterialConsumptionModeResolver.Selection selection, String bom) {
		codec.code(required, "实际分配原需求物料", 100);
		require(rowId != null && rowId > 0 && sourceId != null && sourceId > 0 && demand.containsKey(required),
				"实际分配主键或原需求不可核验");
		var requirement = demand.get(required);
		require(selection.forRequirement(required) ==
				("FEED".equals(kind) ? MesMaterialConsumptionModeResolver.Mode.FEED
						: MesMaterialConsumptionModeResolver.Mode.BACKFLUSH), "实际分配跨越原逐料耗用方式");
		codec.code(material, "原实际物料", 100);
		codec.code(unit, "原实际单位", 100);
		amount(quantity, false, "原实际耗量");
		amount(ratio, false, "原替代比例");
		require(substitute != null, "原替代标记未知，不能按编码猜测");
		var candidates = MesMaterialSubstituteResolver.resolvePolicy(bom, required).candidates();
		require(candidates.stream().anyMatch(c -> material.equals(c.actualMatCode()) && c.substitute() == substitute
				&& equal(c.ratio(), ratio)), "实际分配不属于原BOM允许的主替代物料及比例");
		require(substitute || Objects.equals(requirement.getUnit(), unit), "原主料实际单位与原需求单位不同");
		BigDecimal satisfied;
		try {
			satisfied = amount(quantity.divide(ratio), false, "原精确需求当量");
		} catch (ArithmeticException failure) {
			throw new com.kitex.common.core.exception.CheckedException("原实际分配不能精确换算，禁止补舍入回执");
		}
		require(declaredRequired == null && "STOCK".equals(kind) || equal(satisfied, declaredRequired),
				"原实际耗量与原需求当量不守恒");
		var row = new LinkedHashMap<String, Object>();
		row.put("kind", kind);
		row.put("sourceRowId", rowId.toString());
		row.put("sourceId", sourceId.toString());
		row.put("requirementCode", required);
		row.put("requirementUnit", requirement.getUnit());
		row.put("materialCode", material);
		row.put("materialUnit", unit);
		row.put("quantity", codec.amount(quantity));
		row.put("requirementQuantity", codec.amount(satisfied));
		row.put("ratio", codec.amount(ratio));
		row.put("substitute", substitute);
		return row;
	}

	private BigDecimal amount(BigDecimal value, boolean zero, String label) {
		require(value != null, label + "未知");
		return codec.quantity(codec.amount(value), zero, label);
	}

	private boolean equal(BigDecimal first, BigDecimal second) {
		return first != null && second != null && first.compareTo(second) == 0;
	}

	private String digest(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
