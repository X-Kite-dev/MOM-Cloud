package com.kitex.mom.oms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * WMS正式销售出库对OMS交付行的来源精确、永久幂等回传。
 */
public class OmsDeliveryEventCommand {
    @Data
    @Accessors(chain = true)
    public static class Command {
        private String requestId;
        private Long wmsBillId;
        private String wmsBillNo;
        private Long wmsPlanId;
        private String wmsPlanCode;
        private String orderCode;
        private Long orderDetailId;
        private String factoryCode;
        private String productCode;
        private BigDecimal shippedNum;
        private LocalDateTime shippedTime;
        /**
         * NORMAL计入订单净交付；REPLACEMENT只增加可核验实物流，不重复增加原订单净交付。
         */
        private String shipmentType;
        private Long resolutionCaseId;
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

    /**
     * OMS交付行当前数量与永久收件事件合计，供WMS独立核对。
     */
    @Data
    @Accessors(chain = true)
    public static class LineLedger {
        private String orderCode;
        private Long orderDetailId;
        private String factoryCode;
        private String productCode;
        private BigDecimal orderDeliverNum;
        private BigDecimal omsDeliveredNum;
        private BigDecimal inboxShippedNum;
        private BigDecimal inboxReplacementNum;
        private BigDecimal inboxReturnedNum;
        private BigDecimal inboxCorrectedNum;
        private BigDecimal legacyBaselineNum;
        private Integer inboxCount;
        private Integer replacementInboxCount;
        private Integer returnInboxCount;
        private Integer correctionInboxCount;
    }
}
