package com.kitex.mom.wms.dto;

import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionSubmissionDTO.Query;
import com.kitex.mom.wms.dto.WmsFinishedGoodsCorrectionSubmissionDTO.Receipt;

/**
 * 安全结束未登记申请，不代表撤回或执行已登记纠错。
 */
public final class WmsCorrectionTerminationDTO {
	private WmsCorrectionTerminationDTO() {
	}

	public record EndCommand(Query original, String reason) {
	}

	public record Ended(String terminationId, String tenantId, String factoryCode, String operatorCode,
	                    String warehouseCode, String noticeId, String requestId, String commandFingerprint,
	                    String requestPayload,
	                    String reason, String endedTime) {
	}

	public record Outcome(int contractVersion, String status, Receipt registration, Ended termination) {
	}
}
