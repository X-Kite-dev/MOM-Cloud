package com.kitex.mom.pms.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购收料单进入IQC和WMS前的权威来源快照。
 */
public final class PmsIncomingAvailabilityDTO {

    private PmsIncomingAvailabilityDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotBlank
        private String receiptId;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private String receiptId;
        private String receiptNo;
        private String purchaseOrderNo;
        private String supplierCode;
        private String supplierName;
        private String warehouseCode;
        private String warehouseName;
        private String receiptState;
        private LocalDateTime observedAt;
        private List<BatchLine> batches;
        private List<String> qualityTaskIds;
        private List<String> dataGaps;
        private List<String> limitations;
        private List<String> evidenceRefs;
        private String factFingerprint;
    }

    @Data
    @Accessors(chain = true)
    public static class BatchLine {
        private String receiptBatchId;
        private String purchaseOrderDetailId;
        private String purchasePlanNo;
        private String planSourceIssueMonthId;
        private String planSourceReferenceNo;
        private String materialCode;
        private String materialName;
        private String materialSpec;
        private String materialBatch;
        private String materialUnit;
        private BigDecimal receivedQuantity;
        private BigDecimal inspectedQuantity;
        private BigDecimal inboundQuantity;
        private String receiptLineState;
        private String qualityTaskId;
        private String qualityResult;
        private Boolean qualifiedNoticeGenerated;
        private Boolean unqualifiedNoticeGenerated;
    }
}
