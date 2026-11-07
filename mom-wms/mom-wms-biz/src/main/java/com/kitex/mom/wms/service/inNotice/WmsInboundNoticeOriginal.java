package com.kitex.mom.wms.service.inNotice;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.util.WmsStockOwnership;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 入库来源原件的有效业务内容；执行状态、实收累计和技术标识不参与命令摘要。
 */
public final class WmsInboundNoticeOriginal {
	private static final List<String> HEADER_FIELDS = List.of("billType", "documentTable", "documentId",
			"warehouseCode", "warehouseName", "supplierCode", "workshop", "remark", "expansion");
	private static final List<String> DETAIL_FIELDS = List.of("stockOwnerType", "stockOwnerCode", "sourceBusinessNo",
			"sourceOrderNo", "sourceOrderDetailId", "sourceReceiptId", "sourceReceiptDetailId", "matCode",
			"requirementMatCode", "requirementMatName", "substituteFlag", "substituteRatio", "reservationNo", "matName",
			"matUnit", "matSpec", "matGroup", "matGroupName", "noticeInNum", "warehouseCode", "warehouseName",
			"matProduceDate", "matValidPeriod", "matValidEndDate", "supplierCode", "supplierName", "matBatch",
			"matPackSpec", "operateUnit", "workshop", "sourceQualityTaskIds", "sourceQualityEvidenceFingerprint",
			"sourceQualityEvidenceSnapshot", "documentId", "documentTable", "expansion");

	private WmsInboundNoticeOriginal() {
	}

	public static String canonical(WmsInNoticeEntity notice, List<WmsInNoticeDetailEntity> details) {
		if (notice == null || details == null || details.isEmpty()) {
			throw new CheckedException("入库来源原明细缺失，请核对原通知");
		}
		Map<String, Object> header = projection(notice, HEADER_FIELDS);
		List<String> lines = new ArrayList<>();
		for (WmsInNoticeDetailEntity detail : details) {
			if (detail == null) {
				throw new CheckedException("入库来源原明细不完整");
			}
			Map<String, Object> line = projection(detail, DETAIL_FIELDS);
			// 保存入口由主单确定这些归属；原件和重试正文使用相同的有效值。
			line.put("warehouseCode", scalar(notice.getWarehouseCode()));
			line.put("warehouseName", scalar(notice.getWarehouseName()));
			line.put("workshop", scalar(notice.getWorkshop()));
			line.put("stockOwnerType", detail.getStockOwnerType() == null ||
					detail.getStockOwnerType().isBlank() ? WmsStockOwnership.OWNED : detail.getStockOwnerType().trim());
			// 数据库与既有作业均将未声明替代视为非替代，保持原件的有效业务含义。
			line.put("substituteFlag", Boolean.TRUE.equals(detail.getSubstituteFlag()));
			line.put("stockOwnerCode", trim(detail.getStockOwnerCode()));
			line.put("sourceBusinessNo", trim(detail.getSourceBusinessNo()));
			lines.add(JSONUtil.toJsonStr(line));
		}
		Collections.sort(lines);
		return JSONUtil.toJsonStr(List.of("WMS_INBOUND_ORIGINAL_V2", header, lines));
	}

	public static String fingerprint(WmsInNoticeEntity notice) {
		return sha(canonical(notice, notice.getDetailList()));
	}

	/**
	 * 仅识别既有摘要；是否同一原件仍由完整落库内容核对，绝不将旧摘要升级回写。
	 */
	public static String legacyFingerprint(WmsInNoticeEntity notice) {
		List<String> lines = notice.getDetailList().stream()
				.map(detail -> String.join("~", String.valueOf(detail.getMatCode()),
						String.valueOf(detail.getRequirementMatCode()), String.valueOf(detail.getNoticeInNum()),
						String.valueOf(detail.getWarehouseCode()), String.valueOf(detail.getMatUnit()))).sorted()
				.toList();
		return sha(String.join("|", String.valueOf(notice.getBillType()), String.valueOf(notice.getDocumentTable()),
				String.valueOf(notice.getDocumentId()), String.valueOf(notice.getWarehouseCode()),
				String.valueOf(notice.getWarehouseName()), String.valueOf(notice.getRemark()),
				String.join(";", lines)));
	}

	private static Map<String, Object> projection(Object bean, List<String> fields) {
		Map<String, Object> result = new TreeMap<>();
		for (String field : fields) {
			result.put(field, scalar(BeanUtil.getProperty(bean, field)));
		}
		return result;
	}

	private static Object scalar(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			try {
				return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
			} catch (NumberFormatException error) {
				throw new CheckedException("入库来源含无效数量，请核对原通知");
			}
		}
		if (value instanceof Boolean) {
			return value;
		}
		String text = value.toString();
		return text.isBlank() ? null : text;
	}

	private static String trim(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String sha(String value) {
		try {
			return HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException error) {
			throw new IllegalStateException("SHA-256不可用", error);
		}
	}
}
