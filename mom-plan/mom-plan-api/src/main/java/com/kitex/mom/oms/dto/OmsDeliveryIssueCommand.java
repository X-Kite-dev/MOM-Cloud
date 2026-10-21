package com.kitex.mom.oms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** OMS下发命令：调用结果未知时必须复用同一请求号。 */
public class OmsDeliveryIssueCommand {
    @Data
    public static class Submit { private String requestId; }

    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private String requestId;
        private Long orderDetailId;
        private String mode;
        private String status;
        private String lastError;
        private LocalDateTime completedTime;
    }

    @Data
    @Accessors(chain = true)
    public static class History {
        private String requestId;
        private Long orderDetailId;
        private String mode;
        private String status;
        private String lastError;
        private Integer attemptCount;
        private LocalDateTime submittedTime;
        private LocalDateTime wmsCompletedTime;
        private LocalDateTime completedTime;
    }
}
