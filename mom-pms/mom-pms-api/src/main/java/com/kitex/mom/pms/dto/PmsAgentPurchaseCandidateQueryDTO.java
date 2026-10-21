package com.kitex.mom.pms.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class PmsAgentPurchaseCandidateQueryDTO {
    private String factoryCode;
    private String planDemandNo;
    private String materialCode;
    private LocalDate demandDate;
    private BigDecimal suggestedPurchaseNum;
    private List<String> pmsPlanNos;
}
