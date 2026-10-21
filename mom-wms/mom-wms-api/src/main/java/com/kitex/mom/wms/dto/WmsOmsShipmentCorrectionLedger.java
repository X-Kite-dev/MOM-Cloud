package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * WMS侧销售发运纠错冻结账，供OMS同快照核验。
 */
@Data
@Accessors(chain = true)
public class WmsOmsShipmentCorrectionLedger {
    private Long planId;
    private String planCode;
    private String orderCode;
    private Long orderDetailId;
    private String factoryCode;
    private String productCode;
    private BigDecimal planDeliveredNum;
    private BigDecimal outboxCorrectedNum;
    private Integer outboxCount;
    private String requestId;
    private String requestFingerprint;
    private Long requestCorrectionId;
    private BigDecimal requestCorrectedNum;
}
