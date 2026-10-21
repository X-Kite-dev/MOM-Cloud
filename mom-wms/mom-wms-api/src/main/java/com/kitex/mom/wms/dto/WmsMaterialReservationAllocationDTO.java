package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Accessors(chain = true)
@Schema(description = "物料预留申请分配")
public class WmsMaterialReservationAllocationDTO {
    private String requirementMatCode;
    private String requirementMatName;
    private String actualMatCode;
    private String actualMatName;
    private String actualMatUnit;
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
    private List<String> allowedQualityResults;
    private String complianceMode;
    private String deviationPermitNo;
    private String deviationReason;
    private LocalDate deviationValidEnd;
    @Schema(description = "可选的精确库存行Pegging；存在时WMS必须按这些库存行和数量预留")
    private List<WmsMaterialReservationStockAllocationDTO> stockAllocations;
}
