package com.kitex.mom.oms.dto;

import lombok.Data;

public final class OmsFulfillmentRiskAutomationCommand {
    private OmsFulfillmentRiskAutomationCommand() { }

    @Data
    public static class NoticeResolve {
        private Long noticeId;
        private String requestId;
        private String expectedStatus;
        private String action;
        private String reason;
    }
}
