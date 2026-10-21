package com.kitex.mom.qms.dto.req;

import lombok.Data;

@Data
public class QmsBadVerifyReq {
    private Long detailId;
    private String requestId;
    private String result;
    private String remark;
    private String executionRef;
    private Boolean mesConfirmed;
    private String mesDecisionKey;
}
