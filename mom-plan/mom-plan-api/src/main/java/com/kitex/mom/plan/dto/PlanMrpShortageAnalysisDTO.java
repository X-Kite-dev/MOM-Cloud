package com.kitex.mom.plan.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class PlanMrpShortageAnalysisDTO {
    private LocalDateTime dataAsOf;
    /**
     * 完整Agent精确核验后的产品需求主键；旧问数范围可以为空。
     */
    private Long planDemandId;
    private String planDemandNo;
    private String saleOrderCode;
    private String factoryCode;
    /**
     * 是否已按产品需求主键、原号及当前工厂完成精确绑定。
     */
    private Boolean demandScoped;
    /**
     * 不包含采集时间的确定性事实指纹，用于责任行动重采和漂移提示。
     */
    private String factFingerprint;
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
