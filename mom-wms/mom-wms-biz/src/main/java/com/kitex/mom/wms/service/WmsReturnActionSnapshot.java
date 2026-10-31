package com.kitex.mom.wms.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsProductionReturnReceiptCommand.View;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.entity.WmsProductionReturnReceiptOperationEntity;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 处置和人工续跑的原命令、当次业务结果；不把操作履历再次嵌入快照。 */
public final class WmsReturnActionSnapshot {
	private WmsReturnActionSnapshot() {
	}

	public static Map<String, String> command(WmsInNoticeEntity notice, String type, String decision, String reason) {
		Map<String, String> payload = new LinkedHashMap<>();
		payload.put("noticeId", notice.getId().toString());
		payload.put("warehouseCode", notice.getWarehouseCode());
		payload.put("operationType", type);
		payload.put("decision", decision == null ? "" : decision.trim());
		payload.put("reason", reason.trim());
		return payload;
	}

	public static String freeze(Map<String, String> command, View result, int affectedEvents) {
		result.setOperations(List.of());
		return JSONUtil.createObj().set("contractVersion", 1).set("command", command)
				.set("status", "CONFIRMED").set("affectedEvents", affectedEvents).set("result", result).toString();
	}

	public static View replay(WmsProductionReturnReceiptOperationEntity row, WmsInNoticeEntity actual,
			String operator, Map<String, String> command) {
		try {
			JSONObject snapshot = validateOriginal(row, actual, operator, command);
			require(!"CANCELLED".equals(snapshot.getStr("status")), "原操作请求已撤销，请重新核对后发起新请求");
			JSONObject result = snapshot.getJSONObject("result");
			require(result != null, "原操作结果快照不完整，请核对原履历");
			for (String field : List.of("expectedQuantity", "actualReceivedQuantity", "shortageQuantity", "overageQuantity")) {
				Object quantity = result.get(field);
				require(quantity instanceof Number && Double.isFinite(((Number) quantity).doubleValue())
						&& new BigDecimal(quantity.toString()).signum() >= 0, "原操作数量快照不完整或无效，请核对原履历");
			}
			require(result.get("hasOpenBill") instanceof Boolean, "原操作单据状态快照不完整，请核对原履历");
			View frozen = result.toBean(View.class);
			WmsInNoticeEntity notice = frozen.getNotice();
			require(notice != null && Objects.equals(notice.getId(), actual.getId())
					&& Objects.equals(notice.getTenantId(), actual.getTenantId())
					&& Objects.equals(notice.getFactoryCode(), actual.getFactoryCode())
					&& Objects.equals(notice.getWarehouseCode(), actual.getWarehouseCode())
					&& Objects.equals(notice.getReturnReceiptStatus(), row.getAfterStatus()),
					"原操作结果与原通知范围或状态不一致，请核对原履历");
			BigDecimal expected = notice.getNoticeInNum() == null ? BigDecimal.ZERO : BigDecimal.valueOf(notice.getNoticeInNum());
			BigDecimal overage = notice.getReturnOverageNum() == null ? BigDecimal.ZERO : notice.getReturnOverageNum();
			require(frozen.getExpectedQuantity().compareTo(expected) == 0
					&& frozen.getActualReceivedQuantity().compareTo(expected) <= 0
					&& frozen.getShortageQuantity().compareTo(expected.subtract(frozen.getActualReceivedQuantity())) == 0
					&& frozen.getOverageQuantity().compareTo(overage) == 0,
					"原操作数量与原通知快照不一致，请核对原履历");
			if ("DISPOSITION".equals(row.getOperationType())) {
				require(Objects.equals(notice.getReturnDisposition(), command.get("decision")),
						"原处置结果与原结论不一致，请核对原履历");
			}
			frozen.setOperations(List.of());
			return frozen;
		} catch (CheckedException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new CheckedException("原操作结果快照无法读取，请核对原履历");
		}
	}

	public static JSONObject validateOriginal(WmsProductionReturnReceiptOperationEntity row, WmsInNoticeEntity actual,
			String operator, Map<String, String> command) {
		require(row != null && Objects.equals(row.getNoticeId(), actual.getId())
				&& Objects.equals(row.getTenantId(), actual.getTenantId())
				&& Objects.equals(row.getFactoryCode(), actual.getFactoryCode())
				&& Objects.equals(row.getOperatorCode(), operator)
				&& Objects.equals(row.getOperationType(), command.get("operationType")),
				"原请求不属于当前通知、租户、工厂、操作人或操作类型");
		require(row.getDetail() != null && row.getDetail().stripLeading().startsWith("{"),
				"历史履历未保存完整原结果，请核对原操作履历");
		try {
			JSONObject snapshot = JSONUtil.parseObj(row.getDetail());
			require(Integer.valueOf(1).equals(snapshot.get("contractVersion")), "原操作快照版本无效，请核对原履历");
			JSONObject original = snapshot.getJSONObject("command");
			require(original != null && command.equals(original), "原请求号已被不同内容使用，请核对原结论与原因");
			String status = snapshot.getStr("status");
			require(status == null || "CONFIRMED".equals(status) || "CANCELLED".equals(status), "原操作回执状态无效");
			return snapshot;
		} catch (CheckedException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new CheckedException("原操作结果快照无法读取，请核对原履历");
		}
	}

	public static View copy(View result) {
		return JSONUtil.toBean(JSONUtil.toJsonStr(result), View.class);
	}

	private static void require(boolean valid, String message) {
		if (!valid) {
			throw new CheckedException(message);
		}
	}
}
