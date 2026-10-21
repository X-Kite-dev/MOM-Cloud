package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Accessors(chain = true)
@Schema(description = "MRP替代料候选及建议")
public class PlanMatSubstituteCandidateDTO {
    private String substituteCode;
    private String substituteName;
    private String substituteSpec;
    private String substituteUnit;
    private BigDecimal substituteRatio;
    private Integer priority;
    private BigDecimal availableActualNum;
    private BigDecimal suggestedRequirementNum;
    private BigDecimal suggestedActualNum;
    private List<String> applicableCustomerCodes;
    private List<String> certifiedSupplierCodes;
    private String supplierCertificateRef;
    private LocalDate supplierCertificateValidEnd;
    private String inspectionLevel;
    private BigDecimal referenceUnitPrice;
    private String currency;
    private BigDecimal estimatedSuggestedAmount;
    private BigDecimal costBenchmarkUnitPrice;
    private BigDecimal unitCostVariance;
    private BigDecimal estimatedCostVariance;
    private List<String> allowedQualityResults;
    private String complianceMode;
    private String deviationPermitNo;
    private String deviationReason;
    private LocalDate deviationValidEnd;
    private Boolean complianceEligible;
    private String complianceMessage;
}
