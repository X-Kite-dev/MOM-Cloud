package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesReportCorrectionSettlementEntity;
import com.kitex.mom.mes.entity.MesReportCorrectionSettlementOperationEntity;
import com.kitex.mom.mes.entity.MesReportReverseRequestEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

/**
 * 聚合报工部分纠正差异结案的命令、事实证据和永久履历。
 */
public final class MesReportCorrectionSettlementDTO {
    private MesReportCorrectionSettlementDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class SubmitCommand {
        private Long reverseRequestId;
        @Schema(description = "REPORTED_COMPLETE或COUNTING_ERROR_CONFIRMED")
        private String resolutionType;
        @Schema(maxLength = 64)
        private String requestId;
        @Schema(maxLength = 64)
        private String expectedEvidenceFingerprint;
        @Schema(maxLength = 200)
        private String evidenceReference;
        @Schema(maxLength = 1000)
        private String evidenceDescription;
    }

    @Data
    @Accessors(chain = true)
    public static class ReviewCommand {
        private Long settlementId;
        private Boolean approved;
        @Schema(maxLength = 64)
        private String operationRequestId;
        @Schema(maxLength = 64)
        private String expectedEvidenceFingerprint;
        @Schema(maxLength = 500)
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class WithdrawCommand {
        private Long settlementId;
        @Schema(maxLength = 64)
        private String operationRequestId;
        @Schema(maxLength = 500)
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private MesReportReverseRequestEntity reverseRequest;
        private BigDecimal correctionQuantity;
        private BigDecimal reReportedQuantity;
        private BigDecimal unresolvedQuantity;
        private Integer reReportCount;
        private String permitStatus;
        private String evidenceFingerprint;
        private Boolean reportedCompleteAllowed;
        private Boolean countingErrorAllowed;
        private List<String> blockers;
        private MesReportCorrectionSettlementEntity activeSettlement;
    }

    @Data
    @Accessors(chain = true)
    public static class Detail {
        private MesReportCorrectionSettlementEntity settlement;
        private Evidence currentEvidence;
        private List<MesReportCorrectionSettlementOperationEntity> operations;
    }
}
