package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * OMS接收客户异常结算事件时反查的WMS冻结来源账。
 */
@Data
@Accessors(chain = true)
public class WmsOmsCustomerExceptionResolutionLedger {
    private String requestId;
    private String requestFingerprint;
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
