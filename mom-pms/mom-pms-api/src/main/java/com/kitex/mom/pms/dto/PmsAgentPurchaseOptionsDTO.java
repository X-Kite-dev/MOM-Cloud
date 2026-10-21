package com.kitex.mom.pms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Accessors(chain = true)
public class PmsAgentPurchaseOptionsDTO {
    private String status;
    private Boolean evidenceComplete;
    private String gapMessage;
    private LocalDateTime dataAsOf;
    private List<PmsAgentPurchaseCandidateDTO> candidates;
}
