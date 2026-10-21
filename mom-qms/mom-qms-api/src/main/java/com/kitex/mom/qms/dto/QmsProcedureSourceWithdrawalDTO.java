package com.kitex.mom.qms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MES计划撤回时作废尚未开始的工序质检任务契约。
 */
public final class QmsProcedureSourceWithdrawalDTO {
    private QmsProcedureSourceWithdrawalDTO() {
    }

    @Data
    public static class Command {
        private String requestId;
        private String factoryCode;
        /**
         * MES工序任务编码全集，用于发现未在本地生成账中的孤立QMS任务。
         */
        private List<String> sourceTaskCodes;
        /**
         * MES质检生成账记录的精确QMS任务ID全集。
         */
        private List<Long> expectedTaskIds;
        private Boolean preview;
        private String expectedEvidenceFingerprint;
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private String requestId;
        private String status;
        private Integer cancelledTaskCount;
        private List<Long> taskIds;
        private String evidenceFingerprint;
        private String evidenceSnapshot;
        private LocalDateTime handledTime;
        private String message;
    }
}
