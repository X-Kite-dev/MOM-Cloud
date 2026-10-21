package com.kitex.mom.plan.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Agent审批冻结的跨仓调拨命令。 */
@Data
public class PlanAgentTransferDecisionDTO {
    private String factoryCode;
    private String businessType;
    private String businessNo;
    private Long mrpId;
    private String requestId;
    private String targetWarehouseCode;
    private String materialCode;
    private BigDecimal expectedShortageNum;
    private BigDecimal expectedTargetInTransitNum;
    private String expectedEvidenceFingerprint;
    private String sourceWarehouseCode;
    private Long routePolicyId;
    private String routePolicyCode;
    private String routePolicyFingerprint;
    private String transportMode;
    private String urgencyLevel;
    private Boolean routePolicyRequired;
    private BigDecimal transferNum;
    private List<PlanMrpTransferSuggestionDTO.StockAllocation> allocations;
    private String reason;
}
