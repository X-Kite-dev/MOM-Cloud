package com.kitex.mom.pms.dto;

import com.kitex.mom.pms.entity.PmsContractRevisionOperationEntity;
import com.kitex.mom.pms.entity.PmsContractRevisionRequestEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

public final class PmsContractRevisionDTO {
    private PmsContractRevisionDTO() {
    }

    @Data
    public static class ContentCommand {
        private Long contractId;
        private String title;
        private String contractName;
        private String contractManager;
        private String contractDesc;
        private String contractDemand;
        private String contractBody;
        private String bodyFormat;
        private String legalRepresentative;
        private String address;
        private String bank;
        private String account;
        private String contactPerson;
        private String phoneNumber;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class SubmitCommand extends ContentCommand {
        private String requestId;
        private String expectedEvidenceFingerprint;
        private String reason;
    }

    @Data
    public static class ReviewCommand {
        private Long revisionRequestId;
        private String requestId;
        private String expectedEvidenceFingerprint;
        private Boolean approved;
        private String reason;
    }

    @Data
    public static class WithdrawCommand {
        private Long revisionRequestId;
        private String requestId;
        private String reason;
    }

    @Data
    public static class Preview {
        private Long contractId;
        private String contractNo;
        private String contractStatus;
        private Integer baseVersion;
        private Integer targetVersion;
        private Boolean eligible;
        private List<String> blockers;
        private List<String> changeItems;
        private String changeSummary;
        private String evidenceFingerprint;
        private String evidenceSnapshot;
        private String originalContentSnapshot;
        private String originalContentFingerprint;
        private String targetContentSnapshot;
        private String targetContentFingerprint;
        private Integer receiptCount;
        private Integer activeReceiptCount;
        private Integer settlementCount;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Detail extends PmsContractRevisionRequestEntity {
        private List<PmsContractRevisionOperationEntity> operations;
    }
}
