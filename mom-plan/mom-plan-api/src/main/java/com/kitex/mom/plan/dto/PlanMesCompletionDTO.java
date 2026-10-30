package com.kitex.mom.plan.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * MES提交后投递的完工通知身份；PLAN再向MES核验原件及完整来源进度。
 */
public final class PlanMesCompletionDTO {

    private PlanMesCompletionDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Command {
        private Long outboxId;
        private String eventId;
        private String payloadFingerprint;
        private String factoryCode;
    }

    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private Long outboxId;
        private String eventId;
        private String payloadFingerprint;
        private List<Long> receiptIds;
    }
}
