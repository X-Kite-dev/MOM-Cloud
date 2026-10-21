package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "MRP替代方案决策命令")
public class PlanMatSubstituteDecisionDTO {
    private Long mrpId;
    private String requestId;
    @Schema(description = "APPROVE、REJECT或CANCEL")
    private String action;
    private String reason;
    private List<PlanMatSubstituteAllocationDTO> allocations;
}
