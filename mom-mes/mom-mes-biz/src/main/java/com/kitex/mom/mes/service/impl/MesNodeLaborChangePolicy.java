package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.MdmProcedureLaborStandardDTO;
import com.kitex.mom.mdm.support.MdmProcedureLaborStandardCodec;
import com.kitex.mom.mes.dto.MesEngineeringLaborStandardDTO;
import com.kitex.mom.mes.dto.MesManualLaborChangeDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 普通工单节点标准工时的纯原件政策。只比较保存的原包与精确正式目标，
 * 不读取执行实绩、不判定手工或PLAN资格，也不把标准层当作整包换版。
 */
@Component
@RequiredArgsConstructor
public class MesNodeLaborChangePolicy {
	private static final ObjectMapper JSON = new ObjectMapper()
			.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
			.registerModule(new JavaTimeModule())
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
	private final MesEngineeringPackageService packages;

	/**
	 * 返回原节点与目标节点的批量、四类标准原值和单位；先核完整包只变化该节点标准。
	 */
	public MesManualLaborChangeDTO.Comparison comparison(MesTaskEntity task, MesProduceTaskEntity process,
	                                                     String target, String hash) {
		compare(task, process, target, hash);
		return new MesManualLaborChangeDTO.Comparison(task.getEngineeringContentHash(), hash,
				process.getSourceRouteNodeCode(), process.getRouteCode(),
				standardProjection(labor(task.getEngineeringBundleSnapshot(), process), task, process),
				standardProjection(labor(target, process), task, process));
	}

	/**
	 * 标准值按原BigDecimal转十进制文本，原单位/批量缺证分别保留。
	 */
	public MesManualLaborChangeDTO.Standard standardProjection(String raw, MesTaskEntity task,
	                                                           MesProduceTaskEntity process) {
		var standard = decode(raw, task, process);
		var source = MdmProcedureLaborStandardCodec.read(standard, task.getTenantId().toString(), task.getFactoryCode(),
				process.getRouteCode());
		String quantity = originalValue(source, "activityBasicBatch");
		String unit = originalValue(source, "batchUnit");
		boolean basis = source.path("activityBasicBatch").isNumber() &&
				source.path("activityBasicBatch").decimalValue().signum() > 0 && text(unit, 100);
		var measures = new ArrayList<MesEngineeringLaborStandardDTO.Measure>();
		String[][] fields = {
				{"SETUP", "setupTime"}, {"LABOR", "manualProcessingTime"},
				{"MACHINE", "machineProcessingTime"}, {"DISASSEMBLY", "disassemblyTime"}
		};
		for (var field : fields) {
			String value = originalValue(source, field[1]);
			String timeUnit = originalValue(source, field[1] + "Unit");
			boolean known = value != null && text(timeUnit, 100) &&
					(Set.of("SETUP", "DISASSEMBLY").contains(field[0]) || basis);
			measures.add(new MesEngineeringLaborStandardDTO.Measure(field[0], value, timeUnit,
					known ? "DECLARED" : "UNKNOWN"));
		}
		return new MesManualLaborChangeDTO.Standard(
				new MesEngineeringLaborStandardDTO.Basis(quantity, unit, basis ? "DECLARED" : "UNKNOWN"),
				List.copyOf(measures));
	}

	private String originalValue(JsonNode source, String field) {
		var value = source.get(field);
		return value == null || value.isNull() ? null : value.isNumber() ? value.decimalValue()
				.toPlainString() : value.asText();
	}

	/**
	 * 两侧必须已有合法标准来源；移除唯一目标节点的laborStandard后，整个联合包仍须严格等价。
	 * 原schema、其他节点、BOM、图、SOP、参数和资源字段均不允许被一并改变。
	 */
	public void compare(MesTaskEntity task, MesProduceTaskEntity process, String raw, String hash) {
		packages.readIssuedOriginal(task);
		require(Objects.equals(sha(raw), hash), "正式目标原件指纹不一致");
		ObjectNode before = read(task.getEngineeringBundleSnapshot());
		ObjectNode after = read(raw);
		String oldLabor = labor(before.toString(), process);
		String nextLabor = labor(after.toString(), process);
		require(oldLabor != null && nextLabor != null, "原节点或目标版本缺少已冻结标准来源，不能把历史未知升级为已知");
		decode(oldLabor, task, process);
		decode(nextLabor, task, process);
		require(!packages.sameProjection(oldLabor, nextLabor), "本节点标准来源没有变化");
		stripLabor(before, process.getSourceRouteNodeCode());
		stripLabor(after, process.getSourceRouteNodeCode());
		require(packages.sameProjection(before.toString(), after.toString()),
				"仅允许目标稳定节点标准工时变化；其他节点、材料、BOM、图、参数、SOP及资源必须完全相同");
	}

	/**
	 * 仅删除nodes目标节点标准来源；顶层schema、图中任何字段与其他节点均保持严格等价。
	 */
	private void stripLabor(ObjectNode root, String code) {
		int count = 0;
		for (var node : root.path("nodes")) {
			if (code.equals(node.path("routeNodeCode").asText())) {
				require(node instanceof ObjectNode, "稳定节点来源不是对象");
				((ObjectNode) node).remove("laborStandard");
				count++;
			}
		}
		require(count == 1, "正式包目标稳定节点不存在或重复");
	}

	/**
	 * 从完整原包读取唯一稳定节点标准；重复主工序只按routeNodeCode定位，不猜首条。
	 */
	public String labor(String raw, MesProduceTaskEntity process) {
		var root = read(raw);
		var matches = new ArrayList<JsonNode>();
		for (var node : root.path("nodes")) {
			if (Objects.equals(node.path("routeNodeCode").asText(), process.getSourceRouteNodeCode())) {
				matches.add(node);
			}
		}
		require(matches.size() == 1 &&
						Objects.equals(matches.get(0).path("processCode").asText(), process.getRouteCode()),
				"原标准未唯一归属实际稳定节点");
		JsonNode value = matches.get(0).get("laborStandard");
		return value == null || value.isNull() ? null : value.toString();
	}

	/**
	 * 核原封套schema、字节SHA与租户/工厂/主工序；损坏时拒绝，不回查今日标准。
	 */
	public MdmProcedureLaborStandardDTO decode(String raw, MesTaskEntity task, MesProduceTaskEntity process) {
		try {
			var value = JSON.readValue(raw, MdmProcedureLaborStandardDTO.class);
			MdmProcedureLaborStandardCodec.read(value, task.getTenantId().toString(), task.getFactoryCode(),
					process.getRouteCode());
			return value;
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new CheckedException("标准工时原件损坏，禁止换用当前主数据", failure);
		}
	}

	private ObjectNode read(String raw) {
		require(raw != null && raw.getBytes(StandardCharsets.UTF_8).length <= 8 * 1024 * 1024,
				"来源原件缺失或超过8MiB，禁止截断");
		try {
			var node = JSON.readTree(raw);
			require(node instanceof ObjectNode, "来源原件不是对象");
			return (ObjectNode) node;
		} catch (CheckedException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new CheckedException("来源原件无法完整解析", failure);
		}
	}

	private String sha(String value) {
		require(value != null, "来源原件不能为空");
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception failure) {
			throw new CheckedException("来源指纹无法计算", failure);
		}
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new CheckedException(message);
		}
	}

	private boolean text(String value, int limit) {
		return value != null && !value.isBlank() && value.length() <= limit;
	}
}
