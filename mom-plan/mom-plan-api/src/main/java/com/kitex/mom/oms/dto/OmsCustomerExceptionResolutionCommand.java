package com.kitex.mom.oms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** WMS客户交付异常退款/让步结案到OMS的可靠事件。 */
public final class OmsCustomerExceptionResolutionCommand {
    private OmsCustomerExceptionResolutionCommand() { }
    @Data @Accessors(chain = true)
    public static class Command {
        private String requestId;
        private Long resolutionCaseId;
        private String caseNo;
        private String orderCode;
        private Long orderDetailId;
        private String productCode;
        private BigDecimal refundNum;
        private BigDecimal concessionNum;
        private BigDecimal refundAmount;
        private BigDecimal concessionAmount;
        private String refundReference;
        private String concessionReference;
        private String evidence;
        private LocalDateTime settlementTime;
        private String factoryCode;
    }
    @Data @Accessors(chain = true)
    public static class Receipt {
        private String requestId;
        private String requestFingerprint;
        private String status;
        private BigDecimal omsResolvedExceptionNum;
        private BigDecimal omsSettlementResolvedNum;
        private LocalDateTime completedTime;
    }
}
