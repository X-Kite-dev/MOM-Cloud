package com.kitex.mom.plan.dto;

import lombok.Data;

@Data
public class PlnSchedulingLifecycleCommand {
    private String requestId;
    private String action;
    private String reason;
    private String expectedFingerprint;
}
