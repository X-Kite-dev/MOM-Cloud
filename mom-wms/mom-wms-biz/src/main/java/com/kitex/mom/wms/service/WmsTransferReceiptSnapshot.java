package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.dto.WmsTransferReceiptDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 接收端口共享已发布的原文规范；移动回执仅把长整型和可选空值规范化。 */
public final class WmsTransferReceiptSnapshot {
	private WmsTransferReceiptSnapshot() {
	}

	public static String raw(WmsTransferReceiptDTO.Command command) {
		List<Map<String, Object>> lines = command.getLines() == null ? List.of() : command.getLines().stream().map(line -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("sourceFlowId", line.getSourceFlowId());
			item.put("actual", line.getActualReceivedQuantity());
			item.put("site", StrUtil.trim(line.getTargetSiteCode()));
			item.put("container", StrUtil.trim(line.getTargetContainerCode()));
			return item;
		}).sorted(Comparator.comparing(item -> Objects.toString(item.get("sourceFlowId"), ""))).toList();
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("billId", command.getAllocationBillId());
		snapshot.put("final", command.getFinalReceipt());
		snapshot.put("reason", command.getReasonCode());
		snapshot.put("reasonDescription", StrUtil.trim(command.getReasonDescription()));
		snapshot.put("responsibleType", command.getResponsibleType());
		snapshot.put("evidence", StrUtil.trim(command.getEvidence()));
		snapshot.put("lines", lines);
		return JSONUtil.toJsonStr(snapshot);
	}

	public static String payload(String snapshot) {
		try {
			var original = JSONUtil.parseObj(snapshot);
			require(original.get("billId") instanceof Number && original.get("final") instanceof Boolean);
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("billId", id(original.get("billId")));
			payload.put("final", original.get("final"));
			for (String field : List.of("reason", "reasonDescription", "responsibleType", "evidence")) {
				Object value = original.get(field);
				require(value == null || JSONUtil.isNull(value) || value instanceof String);
				payload.put(field, value instanceof String ? ((String) value).trim() : "");
			}
			var lines = original.getJSONArray("lines");
			require(lines != null && !lines.isEmpty() && lines.size() <= 500);
			List<Map<String, Object>> normalized = new ArrayList<>();
			Set<String> ids = new HashSet<>();
			for (int i = 0; i < lines.size(); i++) {
				var line = lines.getJSONObject(i);
				String source = id(line.get("sourceFlowId"));
				require(ids.add(source) && line.get("actual") instanceof Number);
				BigDecimal quantity = new BigDecimal(line.get("actual").toString());
				require(quantity.signum() >= 0 && Double.isFinite(quantity.doubleValue()));
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("sourceFlowId", source);
				item.put("actual", quantity);
				for (String field : List.of("site", "container")) {
					Object value = line.get(field);
					require(value == null || JSONUtil.isNull(value) || value instanceof String);
					item.put(field, value instanceof String ? ((String) value).trim() : "");
				}
				normalized.add(item);
			}
			normalized.sort(Comparator.comparing(line -> line.get("sourceFlowId").toString()));
			payload.put("lines", normalized);
			return JSONUtil.toJsonStr(payload);
		} catch (RuntimeException exception) {
			throw new CheckedException("原接收回执不完整，请核对接收记录");
		}
	}

	private static String id(Object value) {
		String id = Objects.toString(value, "");
		require(id.matches("[1-9][0-9]{0,18}") && Long.parseLong(id) > 0);
		return id;
	}

	private static void require(boolean valid) {
		if (!valid) {
			throw new CheckedException("原接收快照无效");
		}
	}
}
