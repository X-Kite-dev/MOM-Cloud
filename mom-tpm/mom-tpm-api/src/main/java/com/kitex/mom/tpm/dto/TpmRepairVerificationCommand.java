package com.kitex.mom.tpm.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class TpmRepairVerificationCommand {
    private Long taskId;
    private Long id;
    private String requestId;
    private String expectedFingerprint;
    private String faultReason;
    private String repairMethod;
    private String repairResult;
    private List<Part> parts;
    private String reason;
    private Boolean faultResolved;
    private Boolean trialVerified;
    private Boolean protectionVerified;

    @Data
    public static class Part {
        private String sparePartsCode;
        private BigDecimal useNum;
    }
}
