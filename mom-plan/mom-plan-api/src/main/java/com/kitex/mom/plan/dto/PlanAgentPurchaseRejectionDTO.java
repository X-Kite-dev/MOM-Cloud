package com.kitex.mom.plan.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** PMS Agent采购订单驳回或取消剩余量后回写PLAN MRP的可靠命令与永久回执。 */
public final class PlanAgentPurchaseRejectionDTO {
    private PlanAgentPurchaseRejectionDTO() { }

    @Data
    @Accessors(chain = true)
    public static class Command {
        private String eventId;
        private String sourceFingerprint;
        private String agentRequestId;
        private Long mrpId;
        private Long pmsOrderId;
        private String pmsOrderNo;
        private Long pmsOrderDetailId;
        private String pmsPlanNo;
        private String orderStatus;
        private String rejectReason;
        private LocalDateTime rejectedTime;
        private String factoryCode;
    }

    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private String eventId;
        private Long mrpId;
        private String agentRequestId;
        private String priorOrderStatus;
        /** APPLIED / SUPERSEDED。 */
        private String resultStatus;
        private LocalDateTime handledTime;
        private Boolean duplicate;
        private String message;
    }
}
