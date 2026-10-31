package com.kitex.mom.wms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/** 退料差异处置/人工回写续跑的原请求与当次封存结果。 */
public final class WmsReturnActionDTO {
	private WmsReturnActionDTO() {
	}

	public record Command(@NotBlank String requestId, @NotNull Long noticeId, @NotBlank String warehouseCode,
			@NotBlank String action, String decision, @NotBlank String reason) {
	}

	public record Cancellation(@Valid @NotNull Command command, @NotBlank String reason) {
	}

	public record View(int contractVersion, String requestId, Long noticeId, String warehouseCode,
			String action, String status, String requestPayload, WmsProductionReturnReceiptCommand.View result,
			LocalDateTime completedTime, String cancellationReason) {
	}
}
