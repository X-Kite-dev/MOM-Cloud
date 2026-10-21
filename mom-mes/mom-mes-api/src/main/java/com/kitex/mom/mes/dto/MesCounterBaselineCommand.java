package com.kitex.mom.mes.dto;

import lombok.Data;

@Data
public class MesCounterBaselineCommand {
    private String requestId;
    private Long ruleId;
    private Long caseId;
    private String operation;
    private Long eventId;
    private String arrangeCode;
    private String expectedFingerprint;
    private String reason;
    private String physicalEvidence;
    private Boolean unreportedQuantityHandled;
}
