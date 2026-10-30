package com.kitex.mom.plan.dto;

import lombok.Data;

@Data
public class PlnSchedulingExecutionCommand {
    private String requestId;
    private String reason;
    private String expectedFingerprint;
    private Long parentExecutionId;
    private Integer timeoutSeconds;
    private PlnSchedulingRunDTO run;
}
