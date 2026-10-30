package com.kitex.mom.oms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * OMS交付行撤回：请求号必须由调用方在未知结果重试时复用。
 */
public class OmsDeliveryWithdrawalCommand {
    @Data
    public static class Submit {
        private String requestId;
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private String requestId;
        private Long orderDetailId;
        private String status;
        private String lastError;
        private LocalDateTime completedTime;
    }

    @Data
    @Accessors(chain = true)
    public static class History {
        private String requestId;
        private String orderCode;
        private Long orderDetailId;
        private String status;
        private String reason;
        private String lastError;
        private Integer attemptCount;
        private String applicantName;
        private LocalDateTime submittedTime;
        private LocalDateTime wmsCompletedTime;
        private LocalDateTime completedTime;
    }
}
