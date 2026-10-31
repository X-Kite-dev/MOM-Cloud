package com.kitex.mom.wms.dto;

/**
 * 原办理回执核对；NOT_CONFIRMED不证明原操作未受理，不允许更换原号。
 */
public final class WmsFinishedGoodsCorrectionDecisionDTO {

	private WmsFinishedGoodsCorrectionDecisionDTO() {
	}

	public record Query(String action, Long correctionId, Long noticeId, String warehouseCode,
	                    String requestId, String reason) {
	}

	public record Receipt(int contractVersion, String status, String requestId, String tenantId,
	                      String factoryCode, String operatorCode, String warehouseCode, String noticeId,
	                      String correctionId, String correctionNo, String appliedTime, String quantity,
	                      String commandFingerprint, String requestPayload, String action, String operationType,
	                      String beforeStatus, String afterStatus) {
	}
}
