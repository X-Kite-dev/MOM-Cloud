package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.InOutEnum;
import com.kitex.mom.mes.enums.WarehouseInBizTypeEnum;
import com.kitex.mom.mes.mapper.MesMaterialStartCheckMapper;
import com.kitex.mom.mes.mapper.MesMaterialConsumptionPolicyMapper;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.mapper.MesWarehouseFeedMapper;
import com.kitex.mom.mes.mapper.MesWarehouseInoutMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 同一派工开工后沿真实START_CHECK固定消耗模式；不改变BOM/路线/模板快照，逆向仍恢复原消耗事实。
 */
@Service
@RequiredArgsConstructor
public class MesMaterialConsumptionModeResolver {

	private final MesActualFactoryAccessService access;
	private final RemoteParamService defaults;
	private final MesMaterialStartCheckMapper checks;
	private final MesWarehouseFeedMapper feeds;
	private final MesWarehouseInoutMapper flows;
	private final MesTaskReportMapper reports;
	private final MesTaskMapper tasks;
	private final MesEngineeringPackageService engineering;
	private final MesReworkMaterialPlanService rework;
	private final MesMaterialConsumptionPolicyMapper deployment;
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Set<String> STOCK_CONSUMPTION_TYPES = Set.of(
			WarehouseInBizTypeEnum.BACK_FLUSH_OUT.getCode(), "production_ng_material_out");

	private static long count(Long value) {
		require(value != null && value >= 0, "消耗历史不可核验，不能按没有来源处理");
		return value;
	}

	private static boolean text(String value) {
		return value != null && !value.isBlank();
	}

	private static String digest(String value) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException error) {
			throw new IllegalStateException(error);
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	/**
	 * 实际写入调用者须已持有原父工单/派工锁，预览仅返回当前点时模式。
	 */
	public Mode resolve(MesTaskArrangeEntity arrange) {
		return selection(arrange).mode();
	}

	/**
	 * 首次开工按原工程节点选择；已有开工沿原核验。返回依据供同一次齐套检查保存，禁止另查后拼接。
	 */
	public Selection selection(MesTaskArrangeEntity arrange) {
		String factory = access.require("read");
		Long tenant = TenantContextHolder.getTenantId();
		require(arrange != null && arrange.getId() != null && tenant != null && tenant > 0
						&& !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
						&& Objects.equals(arrange.getTenantId(), tenant) && Objects.equals(arrange.getFactoryCode(), factory)
						&& text(arrange.getCode()) && text(arrange.getTaskCode()) && text(arrange.getProduceTaskCode()),
				"消耗模式缺少真实租户工厂及派工归属");
		long feedCount = count(feeds.selectCount(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
				.eq(MesWarehouseFeedEntity::getTenantId, tenant).eq(MesWarehouseFeedEntity::getFactoryCode, factory)
				.eq(MesWarehouseFeedEntity::getTaskCode, arrange.getTaskCode())
				.eq(MesWarehouseFeedEntity::getArrangeCode, arrange.getCode())));
		long flowCount = count(flows.selectCount(new LambdaQueryWrapper<MesWarehouseInoutEntity>()
				.eq(MesWarehouseInoutEntity::getTenantId, tenant).eq(MesWarehouseInoutEntity::getFactoryCode, factory)
				.eq(MesWarehouseInoutEntity::getBillNo, arrange.getCode())
				.in(MesWarehouseInoutEntity::getBizType, STOCK_CONSUMPTION_TYPES)
				.eq(MesWarehouseInoutEntity::getOperateType, InOutEnum.OUT.getCode())));
		Selection selection;
		if (arrange.getMaterialGateRecordId() != null) {
			selection = anchored(arrange);
		} else {
			long reportCount = count(reports.selectCount(new LambdaQueryWrapper<MesTaskReportEntity>()
					.eq(MesTaskReportEntity::getTenantId, tenant).eq(MesTaskReportEntity::getFactoryCode, factory)
					.eq(MesTaskReportEntity::getArrangeCode, arrange.getCode())));
			require(arrange.getStartTime() == null &&
							Set.of("UN_RECEIVE", "RECEIVE").contains(Objects.toString(arrange.getStatus(), ""))
							&& (arrange.getCompleteNum() == null || arrange.getCompleteNum().signum() == 0)
							&& reportCount == 0 && flowCount == 0,
					"已开工或已有生产来源的派工缺少原开工消耗模式锚点，须受控核对，不能按今日全局配置推断");
			selection = initial(arrange);
		}
		if (selection.perMaterial()) {
			require(Boolean.TRUE.equals(deployment.ready()), "逐物料执行的数据库保护尚未就绪，请完成对应升级后再执行");
			assertPerMaterialHistory(arrange, selection, feedCount, flowCount);
			return selection;
		}
		require(feedCount == 0 || flowCount == 0, "原派工已有投料与倒冲混合历史，须受控核对，禁止重新选择消耗模式");
		Mode mode = selection.mode();
		require(mode != Mode.BACKFLUSH || feedCount == 0, "原派工已有真实投料，不能改走倒冲再次扣减线边库存");
		require(mode != Mode.FEED || flowCount == 0, "原派工已有真实倒冲，不能改走投料再次耗用");
		return selection;
	}

	/**
	 * 新协议只允许不同需求分别耗用；同一需求已有另一模式来源时仍须阻断。
	 */
	private void assertPerMaterialHistory(MesTaskArrangeEntity arrange, Selection policy, long feedCount,
	                                      long flowCount) {
		require(feedCount <= 5000 && flowCount <= 5000, "逐物料消耗历史超过5000行，须按原账核对而非截断");
		if (feedCount > 0) {
			var rows = feeds.selectList(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
					.eq(MesWarehouseFeedEntity::getTenantId, arrange.getTenantId())
					.eq(MesWarehouseFeedEntity::getFactoryCode, arrange.getFactoryCode())
					.eq(MesWarehouseFeedEntity::getTaskCode, arrange.getTaskCode())
					.eq(MesWarehouseFeedEntity::getArrangeCode, arrange.getCode()).last("LIMIT 5001"));
			require(rows != null && rows.size() == feedCount, "原投料历史不完整，不能只凭数量认定逐料方式");
			for (var row : rows) {
				require(row != null && Objects.equals(row.getTenantId(), arrange.getTenantId())
						&& Objects.equals(row.getFactoryCode(), arrange.getFactoryCode())
						&& Objects.equals(row.getTaskCode(), arrange.getTaskCode())
						&& Objects.equals(row.getArrangeCode(), arrange.getCode()), "原投料历史归属不一致");
				String requirement = text(row.getRequirementMatCode()) ? row.getRequirementMatCode()
						: !Boolean.TRUE.equals(row.getSubstituteFlag()) ? row.getMatCode() : null;
				require(policy.forRequirement(requirement) == Mode.FEED, "倒冲需求已有真实投料，禁止再次扣库");
			}
		}
		if (flowCount > 0) {
			var rows = flows.selectList(new LambdaQueryWrapper<MesWarehouseInoutEntity>()
					.eq(MesWarehouseInoutEntity::getTenantId, arrange.getTenantId())
					.eq(MesWarehouseInoutEntity::getFactoryCode, arrange.getFactoryCode())
					.eq(MesWarehouseInoutEntity::getBillNo, arrange.getCode())
					.in(MesWarehouseInoutEntity::getBizType, STOCK_CONSUMPTION_TYPES)
					.eq(MesWarehouseInoutEntity::getOperateType, InOutEnum.OUT.getCode()).last("LIMIT 5001"));
			require(rows != null && rows.size() == flowCount, "原倒冲历史不完整，不能只凭数量认定逐料方式");
			for (var row : rows) {
				require(row != null && Objects.equals(row.getTenantId(), arrange.getTenantId())
						&& Objects.equals(row.getFactoryCode(), arrange.getFactoryCode())
						&& Objects.equals(row.getBillNo(), arrange.getCode())
						&& STOCK_CONSUMPTION_TYPES.contains(row.getBizType())
						&& InOutEnum.OUT.getCode().equals(row.getOperateType())
						&& text(row.getRequirementMatCode()), "原倒冲历史归属或原需求身份不完整");
				require(policy.forRequirement(row.getRequirementMatCode()) == Mode.BACKFLUSH,
						"投料需求已有倒冲流水，禁止再次耗用");
			}
		}
	}

	/**
	 * 原工程正文已有节点设置，不能因今日全局开关或MDM变化丢弃它；旧执行投影保持逐字不改。
	 */
	private Selection initial(MesTaskArrangeEntity arrange) {
		var task = tasks.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
				.eq(MesTaskEntity::getTenantId, arrange.getTenantId())
				.eq(MesTaskEntity::getFactoryCode, arrange.getFactoryCode())
				.eq(MesTaskEntity::getCode, arrange.getTaskCode()));
		require(task != null && task.getId() != null && task.getId() > 0
						&& Objects.equals(task.getTenantId(), arrange.getTenantId())
						&& Objects.equals(task.getFactoryCode(), arrange.getFactoryCode())
						&& Objects.equals(task.getCode(), arrange.getTaskCode())
						&& !"1".equals(task.getDelFlag()),
				"首次开工耗料方式缺少当前组织的真实父工单");
		boolean hasEngineering = task.getEngineeringReleaseId() != null
				|| task.getEngineeringVersionNo() != null || task.getEngineeringContentHash() != null
				|| task.getEngineeringBundleSnapshot() != null;
		if (!hasEngineering) {
			require(arrange.getSourceRouteNodeCode() == null && arrange.getRouteExecutionSchemaVersion() == null,
					"派工已有工程节点或新执行协议但父工单缺工程原件，不能当旧单使用默认");
			return defaultSelection(arrange);
		}
		var original = engineering.readIssuedOriginal(task);
		String nodeCode = arrange.getSourceRouteNodeCode();
		require(nodeCode != null && nodeCode.matches("RN-[1-9][0-9]{0,18}"),
				"原工程派工缺少稳定节点，不能猜测耗料设置");
		assertConsumptionProjection(task, arrange, original);
		try {
			JsonNode selected = null;
			for (JsonNode node : JSON.readTree(task.getEngineeringBundleSnapshot()).path("nodes")) {
				if (nodeCode.equals(node.path("routeNodeCode").asText())) {
					require(selected == null, "原工程存在重复节点耗料来源");
					selected = node;
				}
			}
			require(selected != null && Objects.equals(arrange.getRouteCode(), selected.path("processCode").asText())
							&& selected.has("setting"),
					"原工程节点、主工序或耗料设置声明缺失，不能降级到全局默认");
			JsonNode setting = selected.path("setting");
			if (setting.isNull()) {
				return defaultSelection(arrange);
			}
			require(setting.isObject() && setting.path("id").asText().matches("[1-9][0-9]{0,18}")
							&& arrange.getTenantId().toString().equals(setting.path("tenantId").asText())
							&& arrange.getFactoryCode().equals(setting.path("factoryCode").asText())
							&& task.getProcessRouteCode().equals(setting.path("routeCode").asText())
							&& nodeCode.equals(setting.path("routeNodeCode").asText())
							&& arrange.getRouteCode().equals(setting.path("processCode").asText()),
					"原工程耗料设置身份或布尔值不可核验，须修正工程版本");
			JsonNode schema = setting.path("materialConsumptionSchema");
			if (!schema.isMissingNode() && !schema.isNull()) {
				require(schema.isTextual() && "MATERIAL_CONSUMPTION_V2".equals(schema.textValue())
								&& (setting.path("isBackFlush").isMissingNode() || setting.path("isBackFlush").isNull()),
						"逐物料耗料版本未知或混入旧统一开关，不能降级执行");
				require(!"REWORK".equals(task.getResourceType()),
						"逐物料返工须使用明确逐料方式的批准执行方案，不能借原生产需求推断");
				var modes = requirementModes(selected.path("materials"));
				Mode overall = new java.util.HashSet<>(modes.values()).size() == 1
						? modes.values().iterator().next() : Mode.MIXED;
				return new Selection(overall, "ENGINEERING_MATERIAL_POLICY", task.getEngineeringReleaseId().toString(),
						task.getEngineeringContentHash(), nodeCode, modes);
			}
			require(setting.path("isBackFlush").isBoolean(), "原工程耗料设置布尔值不可核验，不能按今日默认替代");
			Mode mode = setting.path("isBackFlush").booleanValue() ? Mode.BACKFLUSH : Mode.FEED;
			return new Selection(mode, "ENGINEERING_NODE_SETTING", task.getEngineeringReleaseId().toString(),
					task.getEngineeringContentHash(), nodeCode);
		} catch (CheckedException error) {
			throw error;
		} catch (Exception error) {
			throw new CheckedException("原工程耗料设置无法解析，不能读取今日默认替代");
		}
	}

	/**
	 * 下达会合法追加执行图及正式SOP；这里只核本节点用料和BOM，不把派生字段当工程漂移。
	 * 返工用料由已批准执行方案证明，仍从原生产节点取耗料设置，不能借返工换成全局模式。
	 */
	private void assertConsumptionProjection(MesTaskEntity task, MesTaskArrangeEntity arrange,
	                                         MesEngineeringPackageService.FrozenEngineering original) {
		JsonNode actual = executionNode(task.getProcessRouteSnapshot(), arrange);
		if ("REWORK".equals(task.getResourceType())) {
			require(task.getReworkMaterialPlanId() != null && task.getReworkMaterialPlanId() > 0
							&& Objects.equals(task.getReworkRouteNodeCode(), arrange.getSourceRouteNodeCode())
							&& Objects.equals(task.getReworkProcessCode(), arrange.getRouteCode()),
					"返工耗料方式缺少本节点的已批准执行方案，不能借原工程设置放行");
			rework.assertReady(task);
			return;
		}
		JsonNode approved = executionNode(original.routeSnapshot(), arrange);
		require(engineering.sameProjection(actual.path("mdmProcessProcedureMatList").toString(),
						approved.path("mdmProcessProcedureMatList").toString())
						&& engineering.sameProjection(task.getBomSnapshot(), original.bomSnapshot()),
				"本节点用料或BOM已偏离原工程版本，不能按全局开关选择耗料方式");
	}

	private JsonNode executionNode(String snapshot, MesTaskArrangeEntity arrange) {
		try {
			JsonNode rows = JSON.readTree(snapshot);
			require(rows != null && rows.isArray() && !rows.isEmpty() && rows.size() <= 200,
					"耗料方式缺少完整可读的执行路线");
			JsonNode selected = null;
			for (JsonNode row : rows) {
				if (arrange.getSourceRouteNodeCode().equals(row.path("routeNodeCode").asText())) {
					require(selected == null, "执行路线的耗料节点重复");
					selected = row;
				}
			}
			require(selected != null && Objects.equals(arrange.getRouteCode(), selected.path("processCode").asText())
							&& selected.path("mdmProcessProcedureMatList").isArray(),
					"执行路线缺少本稳定节点、主工序或明确用料");
			return selected;
		} catch (CheckedException error) {
			throw error;
		} catch (Exception error) {
			throw new CheckedException("耗料执行路线无法解析，不能读取今日默认替代");
		}
	}

	/**
	 * 只有原版本明确未设置，或完全没有工程身份的旧单，才保留首次开工的既有默认规则。
	 */
	private Selection defaultSelection(MesTaskArrangeEntity arrange) {
		var setting = defaults.getByKey("BACK_FLUSH");
		require(setting != null && setting.isOk() && Set.of("0", "1").contains(Objects.toString(setting.getData(), "")),
				"首次开工的投料/倒冲默认配置不可核验");
		return new Selection("1".equals(setting.getData()) ? Mode.BACKFLUSH : Mode.FEED,
				"LEGACY_GLOBAL_DEFAULT", null, null, arrange.getSourceRouteNodeCode());
	}

	private Selection anchored(MesTaskArrangeEntity arrange) {
		var row = checks.selectOne(new LambdaQueryWrapper<MesMaterialStartCheckEntity>()
				.eq(MesMaterialStartCheckEntity::getId, arrange.getMaterialGateRecordId())
				.eq(MesMaterialStartCheckEntity::getTenantId, arrange.getTenantId())
				.eq(MesMaterialStartCheckEntity::getFactoryCode, arrange.getFactoryCode()));
		require(row != null && Objects.equals(row.getId(), arrange.getMaterialGateRecordId())
				&& Objects.equals(row.getTenantId(), arrange.getTenantId()) &&
				Objects.equals(row.getFactoryCode(), arrange.getFactoryCode())
				&& Objects.equals(row.getArrangeId(), arrange.getId()) &&
				Objects.equals(row.getArrangeCode(), arrange.getCode())
				&& Objects.equals(row.getTaskCode(), arrange.getTaskCode()) &&
				Objects.equals(row.getProduceTaskCode(), arrange.getProduceTaskCode())
				&& "START_CHECK".equals(row.getStage()), "原开工消耗模式锚点缺失、身份不符或不是成功开工核验");
		boolean allowedStart = "READY".equals(row.getResult()) &&
				Set.of("STRICT_REMAINING", "OBSERVE_REMAINING").contains(Objects.toString(row.getMode(), ""))
				|| "SHORTAGE".equals(row.getResult()) && "OBSERVE_REMAINING".equals(row.getMode());
		require(allowedStart && text(row.getManifestJson()) &&
						Objects.equals(row.getFingerprint(), digest(row.getManifestJson())),
				"原开工核验结果或消耗模式原文摘要不可核验");
		try {
			var json = JSONUtil.parseObj(row.getManifestJson());
			if ("MATERIAL_START_V2".equals(json.get("version"))) {
				return anchoredMaterialPolicy(arrange, row, json);
			}
			require("MATERIAL_START_V1".equals(json.get("version"))
							&& String.valueOf(arrange.getId()).equals(json.get("arrangeId"))
							&& row.getMode().equals(json.get("mode")) && row.getResult().equals(json.get("result")),
					"原开工核验合同、派工或策略与消耗模式原文不一致");
			Object mode = json.get("consumptionMode");
			require("FEED".equals(mode) || "BACKFLUSH".equals(mode), "原开工核验缺少明确消耗模式，不能补造历史");
			return new Selection(Mode.valueOf((String) mode), "START_CHECK", row.getId().toString(),
					row.getFingerprint(), arrange.getSourceRouteNodeCode());
		} catch (CheckedException error) {
			throw error;
		} catch (RuntimeException error) {
			throw new CheckedException("原开工消耗模式原文不可解析，不能读取今日全局配置替代");
		}
	}

	private Selection anchoredMaterialPolicy(MesTaskArrangeEntity arrange, MesMaterialStartCheckEntity row,
	                                         cn.hutool.json.JSONObject document) {
		require(String.valueOf(arrange.getId()).equals(document.get("arrangeId"))
						&& row.getMode().equals(document.get("mode")) && row.getResult().equals(document.get("result")),
				"逐物料开工锚点与原派工/核验策略不一致");
		Object raw = document.get("consumptionBasis");
		require(raw instanceof Map<?, ?>, "逐物料开工锚点缺少完整策略");
		Map<?, ?> basis = (Map<?, ?>) raw;
		require("MATERIAL_CONSUMPTION_SELECTION_V2".equals(basis.get("version"))
						&& Objects.equals(arrange.getSourceRouteNodeCode(), basis.get("routeNodeCode"))
						&& Objects.equals(document.get("consumptionMode"), basis.get("mode"))
						&& basis.get("requirementModes") instanceof Map<?, ?>,
				"逐物料开工策略版本、稳定节点或总摘要不完整");
		Map<?, ?> rawModes = (Map<?, ?>) basis.get("requirementModes");
		require(!rawModes.isEmpty() && rawModes.size() <= 100, "逐物料开工策略范围不完整");
		var modes = new java.util.TreeMap<String, Mode>();
		for (var entry : rawModes.entrySet()) {
			require(entry.getKey() instanceof String && entry.getValue() instanceof String
					&& Set.of("FEED", "BACKFLUSH").contains(entry.getValue()), "逐物料锚点存在未知方式");
			modes.put((String) entry.getKey(), Mode.valueOf((String) entry.getValue()));
		}
		var task = tasks.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
				.eq(MesTaskEntity::getTenantId, arrange.getTenantId())
				.eq(MesTaskEntity::getFactoryCode, arrange.getFactoryCode())
				.eq(MesTaskEntity::getCode, arrange.getTaskCode()));
		require(task != null && task.getId() != null && Objects.equals(task.getTenantId(), arrange.getTenantId())
						&& Objects.equals(task.getFactoryCode(), arrange.getFactoryCode()) &&
						Objects.equals(task.getCode(), arrange.getTaskCode())
						&& Objects.equals(document.get("routeFingerprint"),
						digest(Objects.toString(task.getProcessRouteSnapshot(), "")))
						&& Objects.equals(document.get("bomFingerprint"), digest(Objects.toString(task.getBomSnapshot(), ""))),
				"当前原工单用料/BOM已偏离开工策略，不能按今日配置重解释");
		var node = executionNode(task.getProcessRouteSnapshot(), arrange);
		require(modes.equals(requirementModes(node.path("mdmProcessProcedureMatList"))),
				"原开工逐物料方式与原需求集合不同");
		return new Selection(Mode.valueOf((String) basis.get("mode")), "START_CHECK", row.getId().toString(),
				row.getFingerprint(), arrange.getSourceRouteNodeCode(), modes);
	}

	private Map<String, Mode> requirementModes(JsonNode materials) {
		require(materials.isArray() && !materials.isEmpty() && materials.size() <= 100,
				"逐物料耗料须有完整1至100项原需求");
		var result = new java.util.TreeMap<String, Mode>();
		for (var material : materials) {
			JsonNode code = material.path("materialCode"), mode = material.path("consumptionMode");
			require(code.isTextual() && !code.textValue().isBlank() && code.textValue().length() <= 100
							&& code.textValue().equals(code.textValue().trim()) && mode.isTextual()
							&& Set.of("FEED", "BACKFLUSH").contains(mode.textValue()),
					"逐物料耗料缺少明确需求编码或FEED/BACKFLUSH方式");
			require(result.put(code.textValue(), Mode.valueOf(mode.textValue())) == null,
					"逐物料原需求重复，不能合并或猜测消耗方式");
		}
		return result;
	}

	/**
	 * 对已经沿真实原锚点校验的总模式作逐需求分区；不换用今日MDM或全局值。
	 * MIXED必须保留每一原需求的明确方式，不能把未声明行默认为投料。
	 */
	public static java.util.List<com.kitex.mom.mes.dto.ProcessProcedureDTO.ProcessProcedureMatDTO> partition(
			Mode overall, java.util.List<com.kitex.mom.mes.dto.ProcessProcedureDTO.ProcessProcedureMatDTO> requirements,
			Mode requested) {
		require(overall != null && requirements != null && requirements.size() <= 1000
				&& (requested == Mode.FEED || requested == Mode.BACKFLUSH), "耗料需求分区参数不完整");
		if (overall != Mode.MIXED) {
			return overall == requested ? java.util.List.copyOf(requirements) : java.util.List.of();
		}
		var seen = new java.util.HashSet<String>();
		var result = new java.util.ArrayList<com.kitex.mom.mes.dto.ProcessProcedureDTO.ProcessProcedureMatDTO>();
		for (var row : requirements) {
			require(row != null && text(row.getMaterialCode()) && seen.add(row.getMaterialCode())
							&& Set.of("FEED", "BACKFLUSH").contains(Objects.toString(row.getConsumptionMode(), "")),
					"混合耗料存在缺失、重复或未声明方式的原需求");
			if (requested.name().equals(row.getConsumptionMode())) {
				result.add(row);
			}
		}
		return java.util.List.copyOf(result);
	}

	/**
	 * 原文记录号和摘要均为文本；逐料模式完整保存，不把MIXED摘要当作执行规则。
	 */
	public record Selection(
			/** 本次执行摘要：FEED投料、BACKFLUSH倒冲或MIXED逐需求组合。 */
			Mode mode,
			/** 原依据类型，例如START_CHECK、ENGINEERING或旧全局默认。 */
			String source,
			/** 原依据主键；旧默认路径没有原件时为空。 */
			String sourceId,
			/** 原依据SHA指纹；不存在原件时不补造指纹。 */
			String sourceFingerprint,
			/** 原派工稳定路线节点，不以重复主工序编码代替。 */
			String routeNodeCode,
			/** 冻结需求物料到FEED/BACKFLUSH的完整映射；旧统一模式为空集合。 */
			Map<String, Mode> requirementModes) {
		/**
		 * 兼容旧统一耗料方式；不创建逐物料策略。
		 */
		public Selection(Mode mode, String source, String sourceId, String sourceFingerprint, String routeNodeCode) {
			this(mode, source, sourceId, sourceFingerprint, routeNodeCode, Map.of());
		}

		/**
		 * 冻结有序需求映射；摘要与逐需求方式不一致或缺失时拒绝。
		 */
		public Selection {
			require(mode != null && requirementModes != null, "缺少消耗模式选择");
			requirementModes = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(requirementModes));
			if (!requirementModes.isEmpty()) {
				require(requirementModes.values().stream()
								.allMatch(value -> value == Mode.FEED || value == Mode.BACKFLUSH),
						"逐物料原方式不能填写混合摘要");
				Mode overall = new java.util.HashSet<>(requirementModes.values()).size() == 1
						? requirementModes.values().iterator().next() : Mode.MIXED;
				require(mode == overall, "逐物料方式与总摘要不一致");
			} else {
				require(mode != Mode.MIXED, "混合耗料缺少完整逐物料规则");
			}
		}

		/**
		 * 是否持有完整逐需求策略，而非旧统一方式。
		 */
		public boolean perMaterial() {
			return !requirementModes.isEmpty();
		}

		/**
		 * 读取原需求方式；逐料模式下缺编码或需求不在原集合时拒绝。
		 */
		public Mode forRequirement(String code) {
			if (requirementModes.isEmpty()) {
				return mode;
			}
			require(text(code), "原耗料来源缺少明确需求编码");
			var result = requirementModes.get(code);
			require(result != null, "当前需求不在原开工逐物料策略中");
			return result;
		}

		/**
		 * 生成本次开工须原文保存的政策依据；旧V1字段与顺序保留。
		 */
		public Map<String, Object> manifest() {
			var result = new LinkedHashMap<String, Object>();
			result.put("version",
					perMaterial() ? "MATERIAL_CONSUMPTION_SELECTION_V2" : "MATERIAL_CONSUMPTION_SELECTION_V1");
			result.put("mode", mode.name());
			result.put("source", source);
			result.put("sourceId", sourceId);
			result.put("sourceFingerprint", sourceFingerprint);
			result.put("routeNodeCode", routeNodeCode);
			if (perMaterial()) {
				var rows = new java.util.TreeMap<String, String>();
				requirementModes.forEach((code, value) -> rows.put(code, value.name()));
				result.put("requirementModes", rows);
			}
			return result;
		}
	}

	public enum Mode {
		FEED,
		BACKFLUSH,
		MIXED
	}
}
