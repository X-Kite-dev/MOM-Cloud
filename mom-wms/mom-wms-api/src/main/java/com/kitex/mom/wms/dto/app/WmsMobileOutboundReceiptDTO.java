package com.kitex.mom.wms.dto.app;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 移动出库原请求与永久回执。确认库存扣减及拣配登记，不替代实物交付状态。 */
public final class WmsMobileOutboundReceiptDTO {
	private WmsMobileOutboundReceiptDTO() {
	}

	public record Command(@NotBlank String requestId, @NotBlank String warehouseCode,
			@NotBlank String billType, @Valid @NotNull WmsOutBillDetailAppDTO issue) {
	}

	public record Cancellation(@Valid @NotNull Command command, @NotBlank String reason) {
	}

	public record Context(int contractVersion, Long tenantId, String factoryCode, String operatorCode) {
	}

	public record View(int contractVersion, String requestId, String status, Long noticeId,
			String warehouseCode, String billNo, BigDecimal shippedQuantity,
			String requestPayload, LocalDateTime completedTime, String cancellationReason) {
	}
}
