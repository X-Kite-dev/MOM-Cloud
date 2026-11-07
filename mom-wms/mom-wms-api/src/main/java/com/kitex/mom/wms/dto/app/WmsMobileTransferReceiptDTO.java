package com.kitex.mom.wms.dto.app;

import com.kitex.mom.wms.dto.WmsTransferReceiptDTO;
import com.kitex.mom.wms.vo.WmsAllocationBillVO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 标准在途接收的移动合同；即时移库与历史未扣源仓单不混入此入口。
 */
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

	/**
	 * @param contractVersion 当前待接收列表合同版本，为1
	 * @param tenantId        实际租户的完整标识文本
	 * @param factoryCode     已获实名read授权的实际工厂
	 * @param warehouseCode   当前已授权并启用手机接收的目标仓库
	 * @param page            成功读取页码，1至100，每页最多20条
	 * @param hasMore         当前快照是否仍有后续候选，不表示库存或来源已锁定
	 * @param records         已扣源仓、在途或部分接收的标准调拨摘要
	 */
	public record PendingPage(int contractVersion, String tenantId, String factoryCode,
	                          String warehouseCode, int page, boolean hasMore, List<PendingBill> records) {
	}

	/**
	 * @param allocationBillId     实际调拨单主键的完整文本，选中后须与正式来源重核
	 * @param billNo               原实际调拨单号，不以主键伪造单号
	 * @param allocationState      IN_TRANSIT在途、PARTIALLY_RECEIVED部分接收
	 * @param sourceWarehouseCode  原发出仓库编码
	 * @param sourceWarehouseName  原发出仓库名称快照，可为空
	 * @param targetWarehouseCode  当前实际目标仓库编码
	 * @param targetWarehouseName  原目标仓库名称快照，可为空
	 * @param issueTime            原单发出时间，可为空，不用创建时间补造
	 * @param estimatedArrivalTime 原单预计到达时间，可为空
	 * @param remark               原调拨备注，可为空
	 */
	public record PendingBill(String allocationBillId, String billNo, String allocationState,
	                          String sourceWarehouseCode, String sourceWarehouseName,
	                          String targetWarehouseCode, String targetWarehouseName,
	                          LocalDateTime issueTime, LocalDateTime estimatedArrivalTime, String remark) {
	}

	public record View(int contractVersion, Long tenantId, String factoryCode, String operatorCode,
	                   String requestId, String warehouseCode, Long allocationBillId, String billNo,
	                   String status,
	                   Long receiptId, String receiptNo, String receiptStatus, Long varianceCaseId,
	                   String requestPayload, LocalDateTime completedTime, String cancellationReason) {
	}
}
