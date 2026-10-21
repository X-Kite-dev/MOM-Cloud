package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * OMS接收客户实收事件时回查的WMS同源账。
 */
@Data
@Accessors(chain = true)
public class WmsOmsCustomerReceiptLedger {
    private String requestId;
    private String requestFingerprint;
    private Long custodyId;
    private Long outBillId;
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
    private String factoryCode;
}
