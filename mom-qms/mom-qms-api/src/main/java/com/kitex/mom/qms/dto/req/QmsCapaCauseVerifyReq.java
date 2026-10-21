package com.kitex.mom.qms.dto.req;

import lombok.Data;

@Data
public class QmsCapaCauseVerifyReq {
    private String requestId;
    private Long causeId;
    private String result;
    private String remark;
}
