package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Accessors(chain = true)
@Schema(description = "MRP替代料批准分配")
public class PlanMatSubstituteAllocationDTO {
    private String substituteCode;
    private String substituteName;
    private String substituteUnit;
    private BigDecimal substituteRatio;
    private BigDecimal requirementNum;
    private BigDecimal actualNum;
    private List<String> applicableCustomerCodes;
    private List<String> certifiedSupplierCodes;
    private String supplierCertificateRef;
    private LocalDate supplierCertificateValidEnd;
    private String inspectionLevel;
    private BigDecimal referenceUnitPrice;
    private String currency;
    private BigDecimal estimatedAmount;
    private BigDecimal costBenchmarkUnitPrice;
    private BigDecimal unitCostVariance;
    private BigDecimal estimatedCostVariance;
    private List<String> allowedQualityResults;
    private String complianceMode;
    private String deviationPermitNo;
    private String deviationReason;
    private LocalDate deviationValidEnd;
}
