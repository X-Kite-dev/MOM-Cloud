package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * WMS发货计划、销售退货实收与退货Outbox的同快照数量账。
 */
@Data
@Accessors(chain = true)
public class WmsOmsSalesReturnLedger {
    private Long planId;
    private String planCode;
    private String orderCode;
    private Long orderDetailId;
    private String factoryCode;
    private String productCode;
    private BigDecimal planDeliveredNum;
    private BigDecimal planReturnedNum;
    private BigDecimal outboxReturnedNum;
    private BigDecimal legacyReturnBaselineNum;
    private Integer outboxCount;
    private String requestId;
    private String requestFingerprint;
    private Long requestWmsInBillDetailId;
    private Long requestResolutionCaseId;
    private BigDecimal requestReturnedNum;
}
