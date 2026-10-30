package com.kitex.mom.plan.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Agent审批冻结的缺料采购下达命令。
 */
@Data
public class PlanAgentPurchaseOrderDTO {
    private String requestId;
    private String factoryCode;
    private String businessType;
    private String businessNo;
    private String planDemandNo;
    private Long mrpId;
    private String materialCode;
    private String materialName;
    private String materialUnit;
    private String materialSpec;
    private BigDecimal purchaseNum;
    private LocalDate demandDate;
    private LocalDate requestedDeliveryDate;
    private BigDecimal expectedShortageNum;
    private BigDecimal expectedOpenApprovedNum;
    private BigDecimal expectedPendingApprovalNum;
    private BigDecimal expectedInTransitNum;
    private BigDecimal expectedPurchaseUncoveredNum;
    private Integer minOrderQuantity;
    private Integer lotSize;
    private String purchaseMode;
    private String targetPmsPlanNo;
    private Long supplierId;
    private String supplierCode;
    private String supplierName;
    private Integer deliveryLeadTime;
    private LocalDate earliestDeliveryDate;
    private Long authorizationId;
    private String authorizationNo;
    private LocalDate authorizationValidEnd;
    private String inspectionLevel;
    private String currency;
    private Long priceId;
    private BigDecimal unitPriceWithoutTax;
    private BigDecimal unitPriceWithTax;
    private BigDecimal taxRate;
    private String candidateFingerprint;
    private String reason;
}
