package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesMaterialConsumptionTraceDTO.*;
import com.kitex.mom.mes.mapper.MesMaterialConsumptionTraceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Original material consumption evidence. Restorations are separate facts, never subtracted from originals.
 * No current material master or another report domain is used to fill missing historical evidence.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MesMaterialConsumptionTraceService {

	private static final Set<String> KINDS = Set.of("GOOD_FEED", "GOOD_BACKFLUSH", "NG_FEED", "COMBINED_FEED",
			"NG_POLICY_FEED", "NG_POLICY_BACKFLUSH");
	private final MesActualFactoryAccessService access;
	private final MesMaterialConsumptionTraceMapper mapper;

	public Page page(Query input) {
		String factory = access.require("read");
		Long tenant = tenant();
		Query query = normalize(input);
		long total = mapper.total(tenant, factory, query);
		var rows = mapper.page(tenant, factory, query, (long) (query.getCurrent() - 1) * query.getSize(),
				query.getSize());
		var result = new Page().setTenantId(tenant.toString()).setFactoryCode(factory)
				.setObservedAt(LocalDateTime.now()).setQuery(query).setCurrent(query.getCurrent())
				.setSize(query.getSize())
				.setTotal(total).setRecords(rows.stream().map(this::row).toList());
		if (query.getProductIdentifier() != null || query.getReportId() != null) {
			result.getDataGaps()
					.add("当前按生产标识/原报告查询GOOD与NG原耗料；组合根账不作为单件份额，请在工单完整范围查看组合原分摊。");
		}
		if (total == 0) {
			result.getDataGaps().add("未找到匹配的原实耗账；不能据此将投料、领料或当前已用量当作实耗。");
		}
		return result;
	}

	public Detail detail(String kind, String id, String task, String arrange) {
		String factory = access.require("read");
		Long tenant = tenant();
		require(KINDS.contains(kind == null ? "" : kind), "未知实耗来源类型");
		Long sourceId = id(id);
		Query query = normalize(new Query().setTaskCode(task).setArrangeCode(arrange));
		Map<String, Object> original = mapper.original(tenant, factory, query, kind, sourceId);
		require(original != null, "原实耗不存在或不属于当前工单、派工及实际组织");
		return detail(row(original), tenant, factory);
	}

	/**
	 * Agent完整受限范围取证：沿原追溯投影读取四域，每次只核验一次实际组织。
	 * 不把普通分页的一页冒充全量；超限拒绝，不调用生产或恢复命令。
	 */
	@PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
	public List<Detail> workOrderEvidence(String taskCode) {
		String factory = access.require("read");
		Long tenant = tenant();
		Query query = normalize(new Query().setTaskCode(taskCode));
		long count = mapper.total(tenant, factory, query);
		require(count <= 200, "原实耗超过200条完整核验上限，请回原实耗工作台分段调查");
		List<Map<String, Object>> originals = mapper.page(tenant, factory, query, 0, 201);
		require(originals.size() == count, "原实耗完整范围发生变化，不能以部分来源形成核清证明");
		Set<String> identities = new HashSet<>();
		List<Detail> results = new ArrayList<>();
		long factCount = 0;
		for (Map<String, Object> original : originals) {
			Row record = row(original);
			require(identities.add(record.getKey()), "原实耗来源身份重复，不能重复计耗用");
			Detail detail = detail(record, tenant, factory);
			factCount += detail.getAttributions().size() + detail.getRestores().size()
					+ detail.getReversals().size() + detail.getRelatedSources().size();
			require(factCount <= 10000, "原实耗关联凭据超过完整核验上限，不能截断核清");
			results.add(detail);
		}
		return results;
	}

	/**
	 * 公共分页下钻与Agent取证共用同一原账解析，避免再造来源关联口径。
	 */
	private Detail detail(Row record, Long tenant, String factory) {
		String kind = record.getSourceKind();
		Map<String, Object> scope = scope(tenant, factory, record);
		var result = new Detail().setTenantId(tenant.toString()).setFactoryCode(factory)
				.setObservedAt(LocalDateTime.now()).setRecord(record);
		result.setOriginalRoot(fact(rootTable(kind), mapper.root(scope)));
		if (result.getOriginalRoot() == null) {
			result.getDataGaps().add("缺少原来源主账或其组织、任务、派工、请求关联不一致；未从其他报告域补写。");
		}
		Fact rawSource = fact(record.getSourceTable(), mapper.rawSource(scope));
		if (rawSource != null) {
			result.getRelatedSources().add(rawSource);
		}
		Fact supply = fact(("GOOD_BACKFLUSH".equals(kind) ||
						"NG_POLICY_BACKFLUSH".equals(kind)) ? "mes_warehouse_live_stock" : "mes_warehouse_feed",
				mapper.feedOrStock(scope));
		if (supply != null) {
			result.getRelatedSources().add(supply);
		} else {
			result.getDataGaps().add("原投料或精确库存来源已不可读；历史实耗仍保留，不按同批次猜测来源。");
		}
		if ("NG_POLICY_BACKFLUSH".equals(kind)) {
			Fact originalOut = fact("mes_warehouse_inout", mapper.nativePolicyOut(scope));
			if (originalOut == null) {
				result.getDataGaps().add("缺少与原NG分配精确关联的真实OUT；未按当前库存或GOOD域补挂。");
			} else {
				result.getRelatedSources().add(originalOut);
			}
		}
		if ("COMBINED_FEED".equals(kind)) {
			result.setAttributions(
					facts("mes_production_feed_output+mes_production_feed_share", mapper.attributions(scope)));
			if (result.getAttributions().isEmpty()) {
				result.getDataGaps().add("原组合实耗缺少精确产出份额，不能推算GOOD/NG分摊。");
			}
		}
		result.setRestores(facts(restoreTable(kind), mapper.restores(scope)));
		result.setReversals(facts(reversalTable(kind), mapper.reversals(scope)));
		if ("NG_POLICY_BACKFLUSH".equals(kind)) {
			result.getRelatedSources().addAll(facts("mes_warehouse_inout", mapper.nativePolicyRestores(scope)));
		}
		if ("GOOD_FEED".equals(kind) || "GOOD_BACKFLUSH".equals(kind)) {
			var corrections = facts("mes_report_quantity_correction_line", mapper.corrections(scope));
			if (!corrections.isEmpty()) {
				result.getRelatedSources().addAll(result.getRestores());
				result.setRestores(corrections);
				result.getDataGaps()
						.add("纠正分配行的quantity_unit沿原报工单位保存；材料实际单位读取原实耗行，不跨单位合计。");
				if (result.getReversals().isEmpty()) {
					result.getDataGaps().add("原恢复分配存在，但未找到精确关联的纠正申请主账。");
				}
			}
		}
		if ("NG_FEED".equals(kind) && !result.getRestores().isEmpty()) {
			result.getDataGaps().add("NG恢复行按原operation/feed汇集；恢复量不能直接当作本需求行的净耗或独立恢复量。");
		}
		if ("GOOD_BACKFLUSH".equals(kind) && "HAS_RESTORATION".equals(record.getRestorationStatus())
				&& result.getRestores().isEmpty()) {
			result.getDataGaps().add("报告已有冲销，但未找到与原库存主键一致的恢复流水；不按物料批次猜挂。");
		}
		return result;
	}

	private Query normalize(Query input) {
		require(input != null, "请提供实耗查询条件");
		var query = new Query().setTaskCode(code(input.getTaskCode(), true))
				.setProductIdentifier(identifier(input.getProductIdentifier()))
				.setReportId(input.getReportId() == null || input.getReportId().isEmpty() ? null : input.getReportId())
				.setReportDomain(input.getReportDomain() == null ||
						input.getReportDomain().isEmpty() ? null : input.getReportDomain())
				.setArrangeCode(code(input.getArrangeCode(), false))
				.setRequirementMaterialCode(code(input.getRequirementMaterialCode(), false))
				.setActualMaterialCode(code(input.getActualMaterialCode(), false))
				.setBatchNo(code(input.getBatchNo(), false)).setSourceKind(code(input.getSourceKind(), false))
				.setCurrent(input.getCurrent() == null ? 1 : input.getCurrent())
				.setSize(input.getSize() == null ? 20 : input.getSize());
		require(query.getCurrent() >= 1 && query.getSize() >= 1 && query.getSize() <= 100,
				"实耗分页页码须为正数，每页1至100条");
		require(query.getSourceKind() == null || KINDS.contains(query.getSourceKind()), "未知实耗来源类型");
		require((query.getReportId() == null) == (query.getReportDomain() == null), "原报告ID须同时指定GOOD或NG域");
		if (query.getReportId() != null) {
			id(query.getReportId());
			require(Set.of("GOOD", "NG").contains(query.getReportDomain()), "原报告域只允许GOOD或NG");
		}
		require(!"COMBINED_FEED".equals(query.getSourceKind())
						|| query.getProductIdentifier() == null && query.getReportId() == null,
				"组合根账不是单件份额，请清除生产标识/原报告筛选后查看原产出分摊");
		return query;
	}

	/**
	 * 规范生产标识按原文精确查询，不用trim或LIKE把另一个实物匹配进来。
	 */
	private String identifier(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		require(value.equals(value.strip()) && value.length() <= 200
				&& value.chars().noneMatch(Character::isISOControl), "生产标识含首尾空白、控制字符或超过200位");
		return value;
	}

	private String code(String input, boolean required) {
		String value = input == null ? null : input.trim();
		if (value != null && value.isEmpty()) {
			value = null;
		}
		require(!required || value != null, "生产工单号不能为空");
		require(value == null || value.length() <= 100 && value.chars().noneMatch(Character::isISOControl),
				"查询编码超过100位或含控制字符");
		return value;
	}

	private Long tenant() {
		Long tenant = TenantContextHolder.getTenantId();
		require(tenant != null && tenant > 0 && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip()),
				"实耗查询缺少实际租户");
		return tenant;
	}

	private Long id(String value) {
		require(value != null && value.matches("[1-9][0-9]{0,18}"), "实耗来源ID必须为正Long文本");
		try {
			return Long.valueOf(value);
		} catch (NumberFormatException exception) {
			throw new CheckedException("实耗来源ID超出Long范围");
		}
	}

	private Row row(Map<String, Object> source) {
		var result = new Row().setSourceKind(text(source.get("source_kind")))
				.setProductIdentifier(text(source.get("product_identifier")))
				.setProductionReportMode(text(source.get("production_report_mode")))
				.setProductionReportQuantity(text(source.get("production_report_quantity")))
				.setSourceId(text(source.get("source_numeric_id"))).setSourceTable(text(source.get("source_table")))
				.setTaskCode(text(source.get("task_code"))).setProduceTaskCode(text(source.get("produce_task_code")))
				.setArrangeCode(text(source.get("arrange_code"))).setFeedId(text(source.get("feed_id")))
				.setStockId(text(source.get("stock_id"))).setReportId(text(source.get("report_id")))
				.setReportDomain(text(source.get("report_domain"))).setRootId(text(source.get("root_id")))
				.setRequestId(text(source.get("request_id")))
				.setActualMaterialCode(text(source.get("actual_material_code")))
				.setRequirementMaterialCode(text(source.get("requirement_material_code")))
				.setBatchNo(text(source.get("batch_no")))
				.setBarcode(text(source.get("barcode"))).setUnit(text(source.get("unit")))
				.setRequirementUnit(text(source.get("requirement_unit"))).setQuantity(text(source.get("quantity")))
				.setRequirementQuantity(text(source.get("requirement_quantity")))
				.setSourceStatus(text(source.get("source_status")))
				.setRestorationStatus(text(source.get("restoration_status")))
				.setOccurredAt(time(source.get("occurred_at")))
				.setReportLinked(source.get("report_found") != null).setFeedLinked(source.get("feed_found") != null);
		result.setKey(result.getSourceKind() + ":" + result.getSourceId());
		if ("GOOD_BACKFLUSH".equals(result.getSourceKind()) && result.getQuantity() != null) {
			String ratio = text(source.get("substitute_ratio"));
			try {
				if (ratio != null && new BigDecimal(ratio).signum() > 0) {
					result.setRequirementQuantity(
							new BigDecimal(result.getQuantity()).divide(new BigDecimal(ratio)).stripTrailingZeros()
									.toPlainString());
				}
			} catch (ArithmeticException exception) {
				result.getDataGaps().add("原倒冲数量与比例无法形成有限精确需求当量；未舍入补数。");
			}
		}
		if (result.getReportDomain() != null && source.get("report_found") == null) {
			result.getDataGaps()
					.add("原" + result.getReportDomain() + "报告缺失或精确来源关联不一致，未借用另一报告域。");
		}
		if (result.getFeedId() != null && source.get("feed_found") == null) {
			result.getDataGaps().add("原投料来源缺失或归属不一致。");
		}
		if (result.getUnit() == null || result.getRequirementUnit() == null) {
			result.getDataGaps().add("原实际单位或需求单位未完整保存，未知单位保持为空。");
		}
		if (result.getRequirementQuantity() == null) {
			result.getDataGaps().add("原需求当量缺失，不能按当前主数据或默认比例补算。");
		}
		if (result.getSourceStatus() == null) {
			result.getDataGaps().add("原来源状态未知，不能当作当前有效耗用。");
		}
		return result;
	}

	private Map<String, Object> scope(Long tenant, String factory, Row row) {
		Map<String, Object> scope = new LinkedHashMap<>();
		scope.put("tenant", tenant);
		scope.put("factory", factory);
		scope.put("kind", row.getSourceKind());
		scope.put("id", id(row.getSourceId()));
		// API identifiers are text; PostgreSQL comparisons must retain their native BIGINT binding.
		scope.put("rootId", row.getRootId() == null ? null : id(row.getRootId()));
		scope.put("feedId", row.getFeedId() == null ? null : id(row.getFeedId()));
		scope.put("stockId", row.getStockId() == null ? null : id(row.getStockId()));
		scope.put("reportId", row.getReportId() == null ? null : id(row.getReportId()));
		scope.put("task", row.getTaskCode());
		scope.put("produce", row.getProduceTaskCode());
		scope.put("arrange", row.getArrangeCode());
		scope.put("request", row.getRequestId());
		scope.put("material", row.getActualMaterialCode());
		return scope;
	}

	private Fact fact(String table, Map<String, Object> source) {
		if (source == null) {
			return null;
		}
		Map<String, String> fields = new LinkedHashMap<>();
		source.forEach((key, value) -> fields.put(key.toLowerCase(Locale.ROOT), text(value)));
		return new Fact().setTableName(table).setSourceId(fields.get("id")).setFields(fields);
	}

	private List<Fact> facts(String table, List<Map<String, Object>> sources) {
		return sources.stream().map(source -> fact(table, source)).toList();
	}

	private String text(Object value) {
		if (value == null) {
			return null;
		}
		return value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString();
	}

	private LocalDateTime time(Object value) {
		if (value == null) {
			return null;
		}
		return value instanceof Timestamp timestamp ? timestamp.toLocalDateTime() : (LocalDateTime) value;
	}

	private String rootTable(String kind) {
		return switch (kind) {
			case "NG_POLICY_FEED", "NG_POLICY_BACKFLUSH" -> "mes_ng_material_policy_operation";
			case "NG_FEED" -> "mes_production_material_operation";
			case "COMBINED_FEED" -> "mes_production_feed_batch";
			default -> "mes_task_report";
		};
	}

	private String restoreTable(String kind) {
		return switch (kind) {
			case "NG_POLICY_FEED", "NG_POLICY_BACKFLUSH" -> "mes_ng_material_policy_restore";
			case "NG_FEED" -> "mes_production_material_restore";
			case "COMBINED_FEED" -> "mes_production_feed_restore_line";
			case "GOOD_BACKFLUSH" -> "mes_warehouse_inout";
			default -> "mes_report_material_consume";
		};
	}

	private String reversalTable(String kind) {
		return switch (kind) {
			case "NG_POLICY_FEED", "NG_POLICY_BACKFLUSH" -> "mes_ng_material_policy_reversal";
			case "NG_FEED" -> "mes_production_material_reversal";
			case "COMBINED_FEED" -> "mes_production_feed_reversal";
			default -> "mes_report_reverse_request";
		};
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
