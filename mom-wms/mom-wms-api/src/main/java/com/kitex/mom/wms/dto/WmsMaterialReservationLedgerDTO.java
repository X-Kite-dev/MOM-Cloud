package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class WmsMaterialReservationLedgerDTO {
    private String reservationNo;
    private String sourceType;
    private Long sourceId;
    private String sourceNo;
    private String status;
    private String factoryCode;
    private String warehouseCode;
    private String warehouseName;
    private String customerCode;
    private LocalDate requiredDate;
    private BigDecimal reservedNum = BigDecimal.ZERO;
    private BigDecimal consumedNum = BigDecimal.ZERO;
    private BigDecimal returnedNum = BigDecimal.ZERO;
    private BigDecimal releasedNum = BigDecimal.ZERO;
    private BigDecimal activeReservedNum = BigDecimal.ZERO;
    private List<String> integrityIssues = new ArrayList<>();
    private List<WmsMaterialReservationLedgerLineDTO> lines = new ArrayList<>();
}
