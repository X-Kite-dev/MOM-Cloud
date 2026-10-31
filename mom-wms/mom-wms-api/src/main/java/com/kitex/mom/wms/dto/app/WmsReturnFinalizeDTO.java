package com.kitex.mom.wms.dto.app;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 生产退料结束收货的原请求及当次结果，不覆盖后续差异处置。
 */
public final class WmsReturnFinalizeDTO {

	private WmsReturnFinalizeDTO() {
	}

	public record Command(@NotBlank String requestId, @NotNull Long noticeId,
	                      @NotBlank String warehouseCode, BigDecimal overageQuantity, String reason) {
	}

	public record Cancellation(@Valid @NotNull Command command, @NotBlank String reason) {
	}

	/**
	 * 新作业前检；两个版本与原通知范围均须明确匹配，不能替代真实写入授权。
	 */
	public record MobileContext(int contractVersion, int mobileContractVersion, Long tenantId,
	                            String factoryCode, String operatorCode, Long noticeId, String warehouseCode,
	                            boolean mobileEnabled, String mobileMessage) {
	}

	public record Result(String returnReceiptStatus, BigDecimal actualReceivedQuantity,
	                     BigDecimal shortageQuantity, BigDecimal overageQuantity) {
	}

	public record View(int contractVersion, String requestId, String status, Long noticeId,
	                   String warehouseCode, String requestPayload, Result result,
	                   LocalDateTime completedTime, String cancellationReason) {
	}
}
