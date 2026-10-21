package com.kitex.mom.plan.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;

@Data
public class PlanMrpShortageAnalysisDTO {
    private LocalDateTime dataAsOf;
    private String scopeDescription;
    private Integer materialCount;
    private Integer shortageCount;
    private Integer substitutePendingCount;
    private Integer reservationRiskCount;
    private Integer actionRequiredCount;
    private Integer supplyEvidenceGapCount;
    private Integer operationImpactGapCount;
    private Integer transferEvidenceGapCount;
    private Integer transferSuggestedCount;
    private Integer purchaseReadyCount;
    private Integer purchaseEvidenceGapCount;
    private String materialAvailabilityStatus;
    private LocalDate materialAvailableDate;
    private LocalDateTime materialAvailableTime;
    private List<PlanMrpShortageLineDTO> lines;
}
