package com.kitex.mom.qms.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class QmsReworkCompletionDTO {
    private String requestId;
    private String mesTaskCode;
    private String mesProduceTaskCode;
    private BigDecimal completedQuantity;
    private String processCode;
    private String processName;
}
