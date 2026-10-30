package com.kitex.mom.oms.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单履约风险责任闭环命令；未知结果重试必须复用原请求号及原载荷。
 */
public class OmsFulfillmentRiskCommand {

    @Data
    public static class Sync {
        private String requestId;
    }

    @Data
    public static class Assign {
        private String requestId;
        private String ownerCode;
        private LocalDateTime responseDueTime;
        private String actionPlan;
    }

    @Data
    public static class Progress {
        private String requestId;
        private String actionResult;
    }

    @Data
    public static class Close {
        private String requestId;
        private String closeRemark;
    }
}
