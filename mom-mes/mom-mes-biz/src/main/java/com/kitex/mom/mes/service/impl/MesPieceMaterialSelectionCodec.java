package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.mom.mes.dto.MesTaskReportVO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.enums.MesReportChannelEnum;
import com.kitex.mom.mes.enums.ReportModeEnum;

import java.math.BigDecimal;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/** 单件用料公开原文；与报工原号一起冻结，不接受数值型主键或隐式小数转换。 */
public class MesPieceMaterialSelectionCodec {
	private final MesPhysicalTransformCodec physical = new MesPhysicalTransformCodec();

	public List<Selection> decode(String raw) {
		var root = physical.objectDocument(raw, 10000);
		fields(root, Set.of("version", "sources"));
		require(root.path("version").isTextual() && "PIECE_MATERIAL_V1".equals(root.path("version").textValue()),
				"本件用料选择版本未知");
		var sources = root.path("sources");
		require(sources.isArray() && sources.size() > 0 && sources.size() <= 100, "本件用料须明确1至100份来源");
		var ids = new HashSet<Long>();
		var result = new ArrayList<Selection>();
		for (var source : sources) {
			fields(source, Set.of("feedId", "birthId", "birthFingerprint", "quantity"));
			for (var field : List.of("feedId", "birthId", "birthFingerprint", "quantity")) {
				require(source.path(field).isTextual(), "来源主键、出生指纹和数量必须是精确文本");
			}
			Long feedId = physical.id(source.get("feedId").textValue());
			Long birthId = physical.id(source.get("birthId").textValue());
			String fingerprint = source.get("birthFingerprint").textValue();
			require(fingerprint.matches("[0-9a-f]{64}") && ids.add(feedId), "投料来源重复或缺少原出生指纹");
			BigDecimal quantity = physical.quantity(source.get("quantity").textValue(), false, "本件实际耗量");
			require(quantity.scale() <= 6 && quantity.precision() - quantity.scale() <= 14,
					"本件实际耗量超过20位/6位小数，禁止舍入");
			result.add(new Selection(feedId, birthId, fingerprint, quantity));
		}
		return List.copyOf(result);
	}

	/** 原号登记只核稳定传输形状；当前派工与生命周期在锁内另核。 */
	public void command(MesTaskReportVO input, MesReportChannelEnum channel) {
		decode(input.getMaterialSelectionJson());
		require(channel == MesReportChannelEnum.STATION && input.getPassNum() != null && BigDecimal.ONE.compareTo(input.getPassNum()) == 0
				&& input.getExternalIdentifier() == null && input.getExpectedBindingId() == null
				&& input.getExpectedBindingVersion() == null && input.getCarrierOperationId() == null
				&& input.getCarrierCode() == null, "指定本件用料仅支持现场普通单件1件报工");
	}

	public void scope(MesTaskReportVO input, MesTaskArrangeEntity arrange, MesTaskEntity task, boolean bound) {
		if (input.getMaterialSelectionJson() == null) {
			return;
		}
		require(ReportModeEnum.fromCodes(arrange.getReportMode(), arrange.getRouteType()) == ReportModeEnum.ONE_CODE
				&& !"REWORK".equals(task.getResourceType()) && !bound
				&& "NORMAL".equals(input.getExecutionKind()) && Integer.valueOf(1).equals(input.getIdentifierRouteVersion()),
				"指定本件用料须为原正常路线的普通单件报工，返工、退回路线和动态外码须走原流程");
	}

	private void fields(JsonNode node, Set<String> expected) {
		require(node.isObject() && node.size() == expected.size(), "本件用料字段不完整或含未知字段");
		node.fieldNames().forEachRemaining(name -> require(expected.contains(name), "本件用料含未知字段"));
	}

	public record Selection(Long feedId, Long birthId, String birthFingerprint, BigDecimal quantity) {
	}
}
