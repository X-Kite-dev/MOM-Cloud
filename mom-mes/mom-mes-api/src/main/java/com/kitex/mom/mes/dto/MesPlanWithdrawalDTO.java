package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PLAN发起的MES开工前受控撤回契约。
 */
public final class MesPlanWithdrawalDTO {
    private MesPlanWithdrawalDTO() {
    }

    @Data
    public static class Command {
        private String requestId;
        private Long issueId;
        private String issueNo;
        private BigDecimal expectedQuantity;
        private String factoryCode;
        /**
         * true仅校验并返回当前证据，不改变工单。
         */
        private Boolean preview;
        private String expectedEvidenceFingerprint;
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private String requestId;
        private Long issueId;
        private String issueNo;
        private String status;
        private Integer closedTaskCount;
        private List<String> closedTaskCodes;
        private Integer closedProduceTaskCount;
        private Integer closedArrangeCount;
        private Integer cancelledQmsTaskCount;
        private String qmsEvidenceFingerprint;
        private String evidenceFingerprint;
        private LocalDateTime handledTime;
        private String message;
    }
}
