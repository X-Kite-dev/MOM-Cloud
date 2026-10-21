package com.kitex.mom.qms.dto.req;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QmsCapaStageReq {
    private String requestId;
    private Long capaId;
    private String remark;
    private String effectivenessCriteria;
    private LocalDateTime effectivenessReviewTime;
    private String standardizationScope;
}
