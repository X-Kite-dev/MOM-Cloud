package com.kitex.mom.wms.dto.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 即时调拨原操作回执；不以当前库存或后续退回状态替代原结果。 */
public final class WmsAllocationMobileReceiptDTO {

	private WmsAllocationMobileReceiptDTO() {
	}

	/** 取消由服务核对原文；不套用执行阶段的批次、供应商必填约束。 */
	public record Cancellation(@NotNull WmsAllocationBillAppDTO command, @NotBlank String reason) {
	}

	public record View(int contractVersion, Long tenantId, String factoryCode, String operatorCode,
			String requestId, String warehouseCode, String status, Long billId, String billNo,
			String targetWarehouseCode, BigDecimal quantity, String requestPayload,
			LocalDateTime completedTime, String cancellationReason) {
	}
}
