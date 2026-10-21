package com.kitex.mom.oms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** WMS销售退货实收入库对OMS原交付行的来源精确、永久幂等冲减。 */
public class OmsSalesReturnEventCommand {
    @Data
    @Accessors(chain = true)
    public static class Command {
        private String requestId;
        private Long wmsInBillId;
        private Long wmsInBillDetailId;
        private String wmsInBillNo;
        private Long wmsReturnId;
        private Long resolutionCaseId;
        private Long wmsReturnDetailId;
        private Long wmsPlanId;
        private String wmsPlanCode;
        private String orderCode;
        private Long orderDetailId;
        private String factoryCode;
        private String productCode;
        private BigDecimal returnedNum;
        private LocalDateTime returnedTime;
    }

    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private String requestId;
        private Long orderDetailId;
        private String status;
        private BigDecimal omsDeliveredNum;
        private String requestFingerprint;
        private LocalDateTime completedTime;
    }
}
