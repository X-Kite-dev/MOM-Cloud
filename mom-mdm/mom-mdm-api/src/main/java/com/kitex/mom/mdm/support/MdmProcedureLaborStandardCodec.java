package com.kitex.mom.mdm.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.MdmProcedureLaborStandardDTO;
import com.kitex.mom.mdm.entity.MdmProcedureEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

/** 标准工时只保留原值、原单位和原批量依据；缺失不得默认成零、分钟或单件。 */
public final class MdmProcedureLaborStandardCodec {
	public static final String VERSION = "MDM_PROCEDURE_LABOR_STANDARD_V1";
	private static final ObjectMapper JSON = new ObjectMapper()
			.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
	private static final Set<String> FIELDS = Set.of("sourceId", "tenantId", "factoryCode", "processCode", "processName",
			"sourceUpdatedAt", "sourceUpdatedBy", "basisPolicy", "activityBasicBatch", "batchUnit", "setupTime", "setupTimeUnit",
			"manualProcessingTime", "manualProcessingTimeUnit", "machineProcessingTime", "machineProcessingTimeUnit",
			"disassemblyTime", "disassemblyTimeUnit");

	private MdmProcedureLaborStandardCodec() {
	}

	/** 从真实已启用工序行产生有界原文；工程联合版本承担后续批准，不把采集本身称为发布。 */
	public static MdmProcedureLaborStandardDTO freeze(MdmProcedureEntity procedure) {
		require(procedure != null && procedure.getId() != null && procedure.getId() > 0
				&& procedure.getTenantId() != null && procedure.getTenantId() > 0
				&& text(procedure.getFactoryCode()) && text(procedure.getProcessCode())
				&& Boolean.TRUE.equals(procedure.getStatus()) && "0".equals(procedure.getDelFlag()),
				"标准工时来源须为真实组织内唯一已启用工序");
		var body = new LinkedHashMap<String, Object>();
		body.put("sourceId", procedure.getId().toString());
		body.put("tenantId", procedure.getTenantId().toString());
		body.put("factoryCode", procedure.getFactoryCode());
		body.put("processCode", procedure.getProcessCode());
		body.put("processName", procedure.getProcessName());
		body.put("sourceUpdatedAt", procedure.getUpdateTime() == null ? null : procedure.getUpdateTime().toString());
		body.put("sourceUpdatedBy", procedure.getUpdateBy());
		body.put("basisPolicy", "RAW_ACTIVITY_BASIC_BATCH_V1");
		body.put("activityBasicBatch", procedure.getActivityBasicBatch());
		body.put("batchUnit", procedure.getBatchUnit());
		body.put("setupTime", procedure.getSetupTime());
		body.put("setupTimeUnit", procedure.getSetupTimeUnit());
		body.put("manualProcessingTime", procedure.getManualProcessingTime());
		body.put("manualProcessingTimeUnit", procedure.getManualProcessingTimeUnit());
		body.put("machineProcessingTime", procedure.getMachineProcessingTime());
		body.put("machineProcessingTimeUnit", procedure.getMachineProcessingTimeUnit());
		body.put("disassemblyTime", procedure.getDisassemblyTime());
		body.put("disassemblyTimeUnit", procedure.getDisassemblyTimeUnit());
		try {
			var frozen = new MdmProcedureLaborStandardDTO();
			frozen.setSchemaVersion(VERSION);
			frozen.setSourceSnapshot(JSON.writeValueAsString(body));
			frozen.setSourceFingerprint(hash(frozen.getSourceSnapshot()));
			read(frozen, procedure.getTenantId().toString(), procedure.getFactoryCode(), procedure.getProcessCode());
			return frozen;
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new CheckedException("标准工时原文无法完整冻结", failure);
		}
	}

	/** 只核冻结原件，不访问今日工序。空字段明确保留；损坏合同不能降级为历史缺失。 */
	public static JsonNode read(MdmProcedureLaborStandardDTO standard, String tenant, String factory, String process) {
		require(standard != null && VERSION.equals(standard.getSchemaVersion())
				&& standard.getSourceSnapshot() != null && standard.getSourceSnapshot().getBytes(StandardCharsets.UTF_8).length <= 32768
				&& Objects.equals(hash(standard.getSourceSnapshot()), standard.getSourceFingerprint()), "标准工时原件协议或指纹损坏");
		try {
			JsonNode body = JSON.readTree(standard.getSourceSnapshot());
			var actual = new java.util.HashSet<String>();
			body.fieldNames().forEachRemaining(actual::add);
			require(body.isObject() && FIELDS.equals(actual)
					&& tenant.equals(body.path("tenantId").asText()) && factory.equals(body.path("factoryCode").asText())
					&& process.equals(body.path("processCode").asText()) && body.path("sourceId").isTextual()
					&& body.path("sourceId").asText().matches("[1-9][0-9]{0,18}")
					&& "RAW_ACTIVITY_BASIC_BATCH_V1".equals(body.path("basisPolicy").asText()),
					"标准工时原工序身份、组织或批量依据不一致");
			for (String field : new String[]{"setupTime", "manualProcessingTime", "machineProcessingTime", "disassemblyTime"}) {
				JsonNode value = body.get(field);
				require(value.isNull() || value.isNumber() && value.decimalValue().signum() >= 0,
						"标准工时须保留空值或非负原数值，不能把无效量改成零");
			}
			JsonNode batch = body.get("activityBasicBatch");
			require(batch.isNull() || batch.isNumber(), "标准活动批量必须是原数值或明确空值");
			for (String field : new String[]{"batchUnit", "setupTimeUnit", "manualProcessingTimeUnit", "machineProcessingTimeUnit", "disassemblyTimeUnit"}) {
				require(body.get(field).isNull() || body.get(field).isTextual(), "标准单位须保留原文本或明确空值");
			}
			return body;
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new CheckedException("标准工时原文损坏，禁止按缺失资料处理", failure);
		}
	}

	public static boolean captureGeneration(String originalBundle) {
		try {
			JsonNode root = JSON.readTree(originalBundle);
			JsonNode nodes = root.path("nodes");
			require(root.isObject() && nodes.isArray() && !nodes.isEmpty() && nodes.size() <= 200,
					"原工程包节点不完整，不能判断标准工时代际");
			boolean declared = root.has("laborStandardSchemaVersion");
			if (declared) {
				require(root.path("laborStandardSchemaVersion").isTextual()
						&& VERSION.equals(root.path("laborStandardSchemaVersion").asText()), "标准工时代际未知，禁止降级旧包");
			}
			for (JsonNode node : nodes) {
				require(node.isObject() && (declared ? node.path("laborStandard").isObject() : !node.has("laborStandard")),
						"原工程包标准工时声明或部分节点来源缺失，禁止整包降级");
			}
			return declared;
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new CheckedException("原工程包无法判断标准工时代际", failure);
		}
	}

	private static boolean text(String value) {
		return value != null && !value.isBlank();
	}

	private static String hash(String value) {
		require(value != null, "标准工时原文缺失");
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception failure) {
			throw new IllegalStateException("标准工时指纹计算失败", failure);
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}
}
