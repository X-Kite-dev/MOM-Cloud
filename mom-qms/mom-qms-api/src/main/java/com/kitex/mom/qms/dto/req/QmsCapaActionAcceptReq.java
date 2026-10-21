package com.kitex.mom.qms.dto.req;

import lombok.Data;

@Data
public class QmsCapaActionAcceptReq {
    private String requestId;
    private Long actionId;
    private Boolean accepted;
    private String remark;
}
