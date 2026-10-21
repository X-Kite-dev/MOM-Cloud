package com.kitex.mom.pms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class PmsAgentPurchaseCandidateDTO {
    private Long supplierId;
    private String supplierCode;
    private String supplierName;
    private Integer deliveryLeadTime;
    private LocalDate earliestDeliveryDate;
    private Boolean onTime;
    private Long authorizationId;
    private String authorizationNo;
    private LocalDate authorizationValidEnd;
    private String inspectionLevel;
    private String currency;
    private Long priceId;
    private BigDecimal unitPriceWithoutTax;
    private BigDecimal unitPriceWithTax;
    private BigDecimal taxRate;
    private LocalDateTime priceEffectiveTime;
    private LocalDateTime priceExpirationTime;
    /**
     * RELEASE_PLAN复用未下达采购计划；SUPPLEMENT_PLAN由命令新建可追溯补充计划。
     */
    private String purchaseMode;
    private String targetPmsPlanNo;
    private BigDecimal targetPlanRemainingNum;
    private String candidateFingerprint;
}
