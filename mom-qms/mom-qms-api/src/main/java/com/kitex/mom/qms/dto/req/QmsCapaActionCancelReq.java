package com.kitex.mom.qms.dto.req;

import lombok.Data;

@Data
public class QmsCapaActionCancelReq {
    private String requestId;
    private Long actionId;
    private String reason;
}
