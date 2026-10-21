package com.kitex.mom.pms.dto;

import lombok.Data;

@Data
public class PmsDeliveryCommitmentCancelDTO {
    private Long commitmentId;
    private String requestId;
    private String reason;
}
