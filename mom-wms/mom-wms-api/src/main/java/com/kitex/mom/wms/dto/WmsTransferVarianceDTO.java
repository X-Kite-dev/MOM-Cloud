package com.kitex.mom.wms.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class WmsTransferVarianceDTO {
    private WmsTransferVarianceDTO() {
    }

    @Data
    public static class PolicyQuery {
        private Long current;
        private Long size;
        private String policyCode;
        private String sourceWarehouseCode;
        private String targetWarehouseCode;
        private String materialCode;
        private Boolean status;
    }

    @Data
    public static class PolicyCommand {
        private Long id;
        private String policyCode;
        private String policyName;
        private String sourceWarehouseCode;
        private String targetWarehouseCode;
        private String materialCode;
        private BigDecimal shortTolerancePercent;
        private BigDecimal shortToleranceQuantity;
        private BigDecimal overTolerancePercent;
        private BigDecimal overToleranceQuantity;
        private Boolean planReviewOutsideTolerance;
        private Boolean qualityReviewForDamage;
        private Boolean qualityReviewForOver;
        private Integer policyPriority;
        private LocalDate effectiveStartDate;
        private LocalDate effectiveEndDate;
        private Boolean status;
        private String remark;
        private String factoryCode;
    }

    @Data
    public static class CaseQuery {
        private Long current;
        private Long size;
        private String caseNo;
        private String allocationBillNo;
        private String status;
        private String varianceType;
        private String responsibleType;
    }

    @Data
    public static class ReviewCommand {
        private Long caseId;
        private String requestId;
        private String decision;
        private String comment;
        private List<SourceAdjustment> overSourceAdjustments;
    }

    @Data
    public static class SourceAdjustment {
        private Long varianceLineId;
        private Long sourceStockId;
        private BigDecimal quantity;
    }

    @Data
    public static class ResubmitCommand {
        private Long caseId;
        private String requestId;
        private String reasonCode;
        private String reasonDescription;
        private String responsibleType;
        private String evidence;
    }

    @Data
    public static class DispositionCommand {
        private Long caseId;
        private String requestId;
        private String shortDisposition;
        private String overDisposition;
        private String referenceNo;
        private String remark;
        private String supplementRequestId;
        private String supplementBillNo;
        private List<SupplementAllocation> supplementAllocations;
    }

    @Data
    public static class SupplementAllocation {
        private Long varianceLineId;
        private Long sourceStockId;
        private BigDecimal quantity;
    }
}
