package com.kitex.mom.pms.dto;

import com.kitex.mom.pms.entity.PmsContractTerminationOperationEntity;
import com.kitex.mom.pms.entity.PmsContractTerminationRequestEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

public final class PmsContractTerminationDTO {
    private PmsContractTerminationDTO() {
    }

    @Data
    public static class SubmitCommand {
        private Long contractId;
        private String requestId;
        private String expectedEvidenceFingerprint;
        private String reason;
    }

    @Data
    public static class ReviewCommand {
        private Long terminationRequestId;
        private String requestId;
        private String expectedEvidenceFingerprint;
        private Boolean approved;
        private String reason;
    }

    @Data
    public static class Preview {
        private Long contractId;
        private String contractNo;
        private String contractStatus;
        private Long pmsOrderId;
        private String pmsOrderNo;
        private String orderStatus;
        private Boolean eligible;
        private List<String> blockers;
        private String evidenceFingerprint;
        private String evidenceSnapshot;
        private Integer receiptCount;
        private Integer activeReceiptCount;
        private Integer settlementCount;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Detail extends PmsContractTerminationRequestEntity {
        private List<PmsContractTerminationOperationEntity> operations;
    }
}
