package com.kitex.mom.qms.dto.req;

import lombok.Data;

@Data
public class QmsMrbCommand {
    private String requestId;
    private Long detailId;
    private Long proposalId;
    private String operation;
    private String roleCode;
    private String reason;
    private String expectedFingerprint;
    private QmsBadHandleReq disposition;
}
