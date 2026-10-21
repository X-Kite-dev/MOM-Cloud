package com.kitex.mom.oms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** WMS客户实收事件契约；签收事实不重复改写仓库出库数量。 */
public final class OmsCustomerReceiptEventCommand {
    private OmsCustomerReceiptEventCommand() { }
    @Data @Accessors(chain = true)
    public static class Command {
        private String requestId;
        private Long custodyId;
        private String custodyNo;
        private Long outBillId;
        private String outBillNo;
        private Long planId;
        private String planCode;
        private String orderCode;
        private Long orderDetailId;
        private String productCode;
        private BigDecimal signedNum;
        private BigDecimal exceptionNum;
        private BigDecimal resolvedExceptionNum;
        private String shipmentType;
        private Long resolutionCaseId;
        private LocalDateTime receiptTime;
        private String factoryCode;
    }
    @Data @Accessors(chain = true)
    public static class Receipt {
        private String requestId;
        private String requestFingerprint;
        private String status;
        private BigDecimal omsSignedNum;
        private BigDecimal omsExceptionNum;
        private BigDecimal omsResolvedExceptionNum;
        private LocalDateTime completedTime;
    }
}
