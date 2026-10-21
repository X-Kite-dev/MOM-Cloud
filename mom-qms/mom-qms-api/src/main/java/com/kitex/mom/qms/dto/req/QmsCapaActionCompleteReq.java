package com.kitex.mom.qms.dto.req;

import lombok.Data;

@Data
public class QmsCapaActionCompleteReq {
    private String requestId;
    private Long actionId;
    private String result;
    private String evidence;
}
