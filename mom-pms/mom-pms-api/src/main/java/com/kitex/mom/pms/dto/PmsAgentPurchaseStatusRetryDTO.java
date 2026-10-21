package com.kitex.mom.pms.dto;

import lombok.Data;

@Data
public class PmsAgentPurchaseStatusRetryDTO {
    private Long outboxId;
    private String requestId;
    private String reason;
}
