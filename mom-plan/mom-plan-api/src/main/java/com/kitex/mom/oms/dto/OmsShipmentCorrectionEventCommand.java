package com.kitex.mom.oms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * WMS销售发运记账纠错对OMS原交付行的永久幂等反向事件。
 */
public class OmsShipmentCorrectionEventCommand {
    @Data
    @Accessors(chain = true)
    public static class Command {
        private String requestId;
        private Long correctionId;
        private String correctionNo;
        private Long wmsOutBillId;
        private String wmsOutBillNo;
        private Long wmsPlanId;
        private String wmsPlanCode;
        private String orderCode;
        private Long orderDetailId;
        private String factoryCode;
        private String productCode;
        private BigDecimal correctedNum;
        private LocalDateTime correctedTime;
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
