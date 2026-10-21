package com.kitex.mom.plan.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 人工产品需求已接收或仅形成未下达MRP时的受控变更契约。 */
public final class PlanDemandChangeDTO {
    private PlanDemandChangeDTO() {}

    @Data
    public static class SubmitCommand {
        private Long demandId;
        private String requestId;
        private BigDecimal targetQuantity;
        private LocalDate targetDemandDate;
        private String targetWarehouseCode;
        private Integer targetMrpPriority;
        private String expectedEvidenceFingerprint;
        private String reason;
    }

    @Data
    public static class ReviewCommand {
        private Long changeId;
        private String requestId;
        private Boolean approved;
        private String expectedEvidenceFingerprint;
        private String reason;
    }

    @Data
    public static class CancelCommand {
        private Long changeId;
        private String requestId;
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class Preview {
        private Long demandId;
        private String demandNo;
        private String factoryCode;
        private String evidenceFingerprint;
        private String evidenceSnapshot;
        private String targetWarehouseName;
        private String applyMode;
        private String message;
    }
}
