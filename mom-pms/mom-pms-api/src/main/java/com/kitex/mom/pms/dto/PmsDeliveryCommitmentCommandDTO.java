package com.kitex.mom.pms.dto;

import lombok.Data;

import java.util.List;

@Data
public class PmsDeliveryCommitmentCommandDTO {
    private Long orderDetailId;
    private String requestId;
    private String sourceType;
    private String evidenceRef;
    private String reason;
    private List<PmsDeliveryCommitmentItemDTO> items;
}
