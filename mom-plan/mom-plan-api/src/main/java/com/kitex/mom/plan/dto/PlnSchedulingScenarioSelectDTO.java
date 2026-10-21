package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "APS候选方案选择命令")
public class PlnSchedulingScenarioSelectDTO {
    private String requestId;
    private Long candidateTaskId;
    private String expectedSourceFingerprint;
    private String reason;
}
