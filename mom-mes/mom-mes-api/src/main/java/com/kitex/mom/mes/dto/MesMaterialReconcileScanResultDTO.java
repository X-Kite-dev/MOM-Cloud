package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class MesMaterialReconcileScanResultDTO {
    private Integer reservationCount;
    private Integer evidenceLineCount;
    private Integer detectedIssueCount;
    private Integer newIssueCount;
    private Integer reopenedIssueCount;
    private Integer autoResolvedCount;
}
