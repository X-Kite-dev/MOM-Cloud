package com.kitex.mom.pms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PLAN级联撤回采购/外协供应计划的冻结证据与永久幂等回执。
 */
public final class PmsPlanSupplyWithdrawalDTO {
    private PmsPlanSupplyWithdrawalDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Command {
        private String requestId;
        private Long sourceIssueMonthId;
        private Long pmsPlanId;
        private String pmsPlanNo;
        private String planDemandNo;
        private String materialCode;
        private BigDecimal expectedQuantity;
        private String supplyType;
        private String factoryCode;
        private Boolean preview;
        private String expectedEvidenceFingerprint;
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private String requestId;
        private Long sourceIssueMonthId;
        private Long pmsPlanId;
        private String pmsPlanNo;
        /**
         * ELIGIBLE、SUCCEEDED、ALREADY_WITHDRAWN。
         */
        private String status;
        private String evidenceFingerprint;
        private String evidenceSnapshot;
        private String message;
        private LocalDateTime handledTime;
    }
}
