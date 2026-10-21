package com.kitex.mom.mes.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MesMaterialReconcileEvidenceDTO {
    private String reservationNo;
    private String requirementMatCode;
    private String actualMatCode;
    private BigDecimal requestedNum;
    private BigDecimal issuedNum;
    private BigDecimal receivedNum;
    private BigDecimal lineStockNum;
    private BigDecimal fedNum;
    private BigDecimal usedNum;
    private BigDecimal pendingReturnNum;
    private BigDecimal approvedReturnNum;
}
