package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class WmsMaterialReservationLedgerLineDTO {
    private String requirementMatCode;
    private String actualMatCode;
    private String actualMatName;
    private BigDecimal substituteRatio;
    private BigDecimal reservedNum = BigDecimal.ZERO;
    private BigDecimal consumedNum = BigDecimal.ZERO;
    private BigDecimal returnedNum = BigDecimal.ZERO;
    private BigDecimal releasedNum = BigDecimal.ZERO;
    private BigDecimal activeReservedNum = BigDecimal.ZERO;
    private BigDecimal physicalStockNum = BigDecimal.ZERO;
    private BigDecimal unavailableActiveNum = BigDecimal.ZERO;
    private List<String> reservedSupplierCodes = new ArrayList<>();
    private List<String> reservedQualityResults = new ArrayList<>();
    private String complianceMode;
    private String complianceReferenceNo;
    private LocalDate complianceValidEnd;
    private List<String> integrityIssues = new ArrayList<>();
}
