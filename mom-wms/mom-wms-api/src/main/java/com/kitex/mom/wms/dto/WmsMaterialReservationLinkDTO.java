package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Accessors(chain = true)
public class WmsMaterialReservationLinkDTO {
    private String reservationNo;
    private String warehouseCode;
    private String requirementMatCode;
    private String actualMatCode;
    private BigDecimal substituteRatio;
    private BigDecimal reservedActualNum;
    private List<String> certifiedSupplierCodes;
    private List<String> allowedQualityResults;
    private String complianceMode;
    private String complianceReferenceNo;
    private LocalDate complianceValidEnd;
    private String inspectionLevel;
}
