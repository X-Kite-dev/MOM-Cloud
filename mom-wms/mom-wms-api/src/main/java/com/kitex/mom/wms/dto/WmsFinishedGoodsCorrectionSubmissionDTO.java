package com.kitex.mom.wms.dto;

/**
 * 原申请登记证据；REGISTERED不表示库存已红冲或MES/QMS已确认。
 */
public final class WmsFinishedGoodsCorrectionSubmissionDTO {

	private WmsFinishedGoodsCorrectionSubmissionDTO() {
	}

	public record Query(String warehouseCode, WmsFinishedGoodsReceiptCorrectionCommand command) {
	}

	public record Receipt(int contractVersion, String status, String requestId, String tenantId,
	                      String factoryCode, String operatorCode, String warehouseCode, String noticeId,
	                      String correctionId, String correctionNo, String appliedTime, String quantity,
	                      String commandFingerprint, String requestPayload) {
	}
}
