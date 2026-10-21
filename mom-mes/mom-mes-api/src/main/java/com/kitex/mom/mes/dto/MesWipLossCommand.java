package com.kitex.mom.mes.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MesWipLossCommand {
    private String operation;
    private String requestId;
    private Long transferId;
    private Long caseId;
    private Long expectedRevision;
    private BigDecimal quantity;
    private String investigationEvidence;
    private String responsibility;
    private String prevention;
    private String reason;
    private Boolean searchedConfirmed;
    private Boolean noReceiptConfirmed;
    private Boolean decisionConfirmed;
}
