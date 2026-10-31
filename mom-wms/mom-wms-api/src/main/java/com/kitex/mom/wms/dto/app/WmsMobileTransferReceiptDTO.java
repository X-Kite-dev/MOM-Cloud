package com.kitex.mom.wms.dto.app;

import com.kitex.mom.wms.dto.WmsTransferReceiptDTO;
import com.kitex.mom.wms.vo.WmsAllocationBillVO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/** 标准在途接收的移动合同；即时移库与历史未扣源仓单不混入此入口。 */
public final class WmsMobileTransferReceiptDTO {
	private WmsMobileTransferReceiptDTO() {
	}

	public record Context(int contractVersion, Long tenantId, String factoryCode, String operatorCode) {
	}

	public record Command(@NotBlank String warehouseCode, @NotNull WmsTransferReceiptDTO.Command receipt) {
	}

	public record Cancellation(@NotNull Command command, @NotBlank String reason) {
	}

	public record Source(Context identity, String warehouseCode, WmsAllocationBillVO bill) {
	}

	public record View(int contractVersion, Long tenantId, String factoryCode, String operatorCode,
			String requestId, String warehouseCode, Long allocationBillId, String billNo, String status,
			Long receiptId, String receiptNo, String receiptStatus, Long varianceCaseId,
			String requestPayload, LocalDateTime completedTime, String cancellationReason) {
	}
}
