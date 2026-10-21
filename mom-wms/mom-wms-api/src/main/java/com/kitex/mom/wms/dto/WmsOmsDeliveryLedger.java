package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * WMS发货计划与正式销售出库Outbox的同快照数量账。
 */
@Data
@Accessors(chain = true)
public class WmsOmsDeliveryLedger {
    private Long planId;
    private String planCode;
    private String orderCode;
    private Long orderDetailId;
    private String factoryCode;
    private String productCode;
    private BigDecimal planDeliveredNum;
    private BigDecimal outboxShippedNum;
    private BigDecimal outboxReplacementNum;
    private BigDecimal outboxCorrectedNum;
    private BigDecimal legacyBaselineNum;
    private Integer outboxCount;
    private Integer replacementOutboxCount;
    private String requestId;
    private String requestFingerprint;
    private Long requestWmsBillId;
    private BigDecimal requestShippedNum;
    private String requestShipmentType;
    private Long requestResolutionCaseId;
}
