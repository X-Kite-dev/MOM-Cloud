package com.kitex.mom.wms.service.special;

import com.kitex.mom.wms.entity.WmsInoutFlowEntity;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

import java.util.*;

import static com.kitex.mom.wms.service.special.WmsSpecialRules.*;

/**
 * 冻结原件的物理身份与首次来源。标识统一为文本，不经Double或当前主数据重建。
 */
public final class WmsSpecialStockProof {
	private WmsSpecialStockProof() {
	}

	private static final List<String> PROPERTIES = List.of("matCode", "matName", "matUnit", "matBatch",
			"matBarcode",
			"serialManaged", "supplierCode", "supplierName", "matGroup", "matGroupName", "matSpec",
			"matPackSpec",
			"matProduceDate", "matValidPeriod", "matValidEndDate", "stockOwnerType", "stockOwnerCode",
			"sourceBusinessNo", "sourceInBillId", "sourceInBillDetailId", "sourceMesInboundRequestId",
			"sourceOrderNo",
			"sourceOrderDetailId", "sourceReceiptId", "sourceReceiptDetailId", "qualityResult", "qualityStatus",
			"qualityHoldNo", "qualitySourceType", "qualitySourceId", "isQualityTest", "qualityTaskId",
			"sourceQualityTaskIds", "sourceQualityEvidenceFingerprint", "sourceQualityEvidenceSnapshot",
			"expirationStatus", "slowMaterialStatus");
	private static final Set<String> LABELS = Set.of("matName", "supplierName", "matGroup", "matGroupName",
			"matSpec",
			"matPackSpec");

	public static Map<String, String> identity(Object source) {
		var bean = new BeanWrapperImpl(source);
		var result = new TreeMap<String, String>();
		for (var property : PROPERTIES) {
			Object value = bean.isReadableProperty(property) ? bean.getPropertyValue(property) : null;
			String encoded = value == null ? "" : value.toString();
			if ("serialManaged".equals(property)) {
				encoded = Boolean.TRUE.equals(value) ? "true" : "false";
			}
			if ("stockOwnerType".equals(property) && encoded.isBlank()) {
				encoded = "OWNED";
			}
			result.put(property, encoded);
		}
		Object time = bean.getPropertyValue(source instanceof WmsInoutFlowEntity ? "stockInTime" : "inTime");
		result.put("inTime", time == null ? "" : time.toString());
		return Collections.unmodifiableMap(result);
	}

	public static void matches(Map<String, String> original, Object actual) {
		require(original != null, "原库存身份缺失");
		var current = identity(actual);
		require(current.keySet().equals(original.keySet()) &&
						current.entrySet().stream().filter(e -> !LABELS.contains(e.getKey()))
								.allMatch(e -> Objects.equals(e.getValue(), original.get(e.getKey()))),
				"当前实物身份、质量或首次来源与原件不一致");
	}

	public static boolean sameCurrentStock(Map<String, String> original, WmsLiveStockEntity stock) {
		if (original == null) {
			return false;
		}
		var current = identity(stock);
		return current.keySet().equals(original.keySet()) && current.entrySet().stream()
				.filter(e -> !LABELS.contains(e.getKey()) && !e.getKey().startsWith("quality") &&
						!Set.of("isQualityTest", "expirationStatus", "slowMaterialStatus").contains(e.getKey()))
				.allMatch(e -> Objects.equals(e.getValue(), original.get(e.getKey())));
	}

	static void copySource(Object source, Object target) {
		BeanWrapper from = new BeanWrapperImpl(source);
		BeanWrapper to = new BeanWrapperImpl(target);
		for (var property : PROPERTIES) {
			if (from.isReadableProperty(property) && to.isWritableProperty(property)) {
				to.setPropertyValue(property, from.getPropertyValue(property));
			}
		}
	}

	static void copyFirstSource(WmsLiveStockEntity stock, WmsInoutFlowEntity flow) {
		copySource(stock, flow);
		flow.setStockInTime(stock.getInTime());
	}
}
