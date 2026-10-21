package com.kitex.mom.tpm.dto;

import lombok.Data;

public final class TpmCheckTaskDTO {
    private TpmCheckTaskDTO() {
    }

    @Data
    public static class ReleaseCommand {
        private Long taskId;
        private String requestId;
        private String releaseReason;
        private String releaseEvidence;
        private String relatedRepairTaskCode;
    }
}
