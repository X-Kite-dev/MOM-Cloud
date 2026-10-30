package com.kitex.mom.wms.dto.app;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 移动收货原请求与永久回执。确认数量登记，不替代原入库单的过账状态。 */
public final class WmsMobileInboundReceiptDTO {
	private WmsMobileInboundReceiptDTO() {
	}

	public record Command(@NotBlank String requestId, @NotBlank String warehouseCode,
			@NotBlank String billType, @Valid @NotNull WmsInBillDetailAppDTO receipt) {
	}

	public record Cancellation(@Valid @NotNull Command command, @NotBlank String reason) {
	}

	public record Context(int contractVersion, Long tenantId, String factoryCode, String operatorCode) {
	}

	public record View(int contractVersion, String requestId, String status, Long noticeId,
			String warehouseCode, String billNo, BigDecimal receivedQuantity,
			String requestPayload, LocalDateTime completedTime, String cancellationReason) {
	}
}
