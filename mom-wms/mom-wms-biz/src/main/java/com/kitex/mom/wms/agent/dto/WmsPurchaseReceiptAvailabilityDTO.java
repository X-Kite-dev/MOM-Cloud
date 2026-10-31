package com.kitex.mom.wms.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一张采购收料单在WMS中的通知、正式入库和当前库存来源证据。
 */
public final class WmsPurchaseReceiptAvailabilityDTO {

    private WmsPurchaseReceiptAvailabilityDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotBlank
        private String receiptId;
        private Integer detailLimit;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private String receiptId;
        private LocalDateTime observedAt;
        private long noticeLineCount;
        private long inboundLineCount;
        private long approvedInboundLineCount;
        private long currentStockRowCount;
        private List<NoticeLine> noticeLines;
        private List<InboundLine> inboundLines;
        private List<StockLine> currentStocks;
        private List<String> dataGaps;
        private List<String> limitations;
        private List<String> evidenceRefs;
        private Boolean truncated;
        private String factFingerprint;
    }

    @Data
    @Accessors(chain = true)
    public static class NoticeLine {
        private String noticeId;
        private String noticeDetailId;
        private String noticeState;
        private String receiptBatchId;
        private String purchaseOrderDetailId;
        private String materialCode;
        private String materialBatch;
        private String materialUnit;
        private BigDecimal noticeQuantity;
        private BigDecimal inboundQuantity;
        private BigDecimal pendingQuantity;
        private String qualityTaskId;
        private String qualityResult;
    }

    @Data
    @Accessors(chain = true)
    public static class InboundLine {
        private String inboundBillId;
        private String inboundBillNo;
        private String inboundState;
        private String inboundDetailId;
        private String receiptBatchId;
        private String purchaseOrderDetailId;
        private String materialCode;
        private String materialBatch;
        private String materialUnit;
        private BigDecimal inboundQuantity;
        private String warehouseCode;
        private String locationCode;
        private String qualityTaskId;
        private String qualityResult;
        private LocalDateTime inboundTime;
    }

    @Data
    @Accessors(chain = true)
    public static class StockLine {
        private String stockId;
        private String receiptBatchId;
        private String purchaseOrderDetailId;
        private String materialCode;
        private String materialBatch;
        private String materialUnit;
        private BigDecimal currentQuantity;
        private String warehouseCode;
        private String locationCode;
        private Integer lockStatus;
        private String qualityStatus;
        private String qualityResult;
    }
}
