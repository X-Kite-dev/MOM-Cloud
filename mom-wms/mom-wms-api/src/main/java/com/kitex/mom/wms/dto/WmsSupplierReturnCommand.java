package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购到货后退供在 PMS 与 WMS 之间传递的受控命令。
 *
 * <p>库存候选和通知创建都以实时库存行主键为边界，禁止只按物料编码模糊退货。</p>
 */
public final class WmsSupplierReturnCommand {

    private WmsSupplierReturnCommand() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "退供可用库存查询")
    public static class StockQuery {
        private String orderNo;
        private Long orderDetailId;
        private String materialCode;
        private String supplierCode;
        private String warehouseCode;
        private String batchNo;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "可用于退供的精确库存行")
    public static class StockCandidate {
        private Long liveStockId;
        private String sourceOrderNo;
        private Long sourceOrderDetailId;
        private Long sourceReceiptId;
        private Long sourceReceiptDetailId;
        private String materialCode;
        private String materialName;
        private String materialSpec;
        private String materialUnit;
        private String supplierCode;
        private String supplierName;
        private String warehouseCode;
        private String warehouseName;
        private String siteCode;
        private String siteName;
        private String containerCode;
        private String batchNo;
        private String qualityStatus;
        private String qualityResult;
        private LocalDate validEndDate;
        private LocalDateTime stockInTime;
        private BigDecimal physicalQuantity;
        private BigDecimal productionReservedQuantity;
        private BigDecimal salesReservedQuantity;
        private BigDecimal returnableQuantity;
        private String stockFingerprint;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "创建采购退供出库通知")
    public static class CreateNotice {
        private String requestId;
        private Long returnId;
        private String returnNo;
        private String orderNo;
        private String supplierCode;
        private String supplierName;
        private String reason;
        private String factoryCode;
        private String sourceFingerprint;
        private List<NoticeLine> lines;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "采购退供通知明细")
    public static class NoticeLine {
        private Long returnDetailId;
        private Long orderDetailId;
        private String planNo;
        private Long liveStockId;
        private Long sourceReceiptId;
        private Long sourceReceiptDetailId;
        private String materialCode;
        private String materialName;
        private String materialSpec;
        private String materialUnit;
        private String supplierCode;
        private String warehouseCode;
        private String warehouseName;
        private String siteCode;
        private String siteName;
        private String containerCode;
        private String batchNo;
        private String qualityStatus;
        private String qualityResult;
        private LocalDate validEndDate;
        private LocalDateTime stockInTime;
        private BigDecimal returnQuantity;
        private String stockFingerprint;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "采购退供出库通知永久回执")
    public static class NoticeReceipt {
        private Long returnId;
        private Long noticeId;
        private String noticeStatus;
        private String sourceFingerprint;
        private LocalDateTime acceptedTime;
    }
}
