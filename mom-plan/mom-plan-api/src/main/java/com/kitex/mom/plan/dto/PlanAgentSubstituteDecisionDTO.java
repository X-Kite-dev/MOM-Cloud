package com.kitex.mom.plan.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PlanAgentSubstituteDecisionDTO {
    private String factoryCode;
    private String businessType;
    private String businessNo;
    private String expectedSubstituteStatus;
    private BigDecimal expectedShortageNum;
    private String expectedSubstituteSuggestion;
    private PlanMatSubstituteDecisionDTO decision;
}
