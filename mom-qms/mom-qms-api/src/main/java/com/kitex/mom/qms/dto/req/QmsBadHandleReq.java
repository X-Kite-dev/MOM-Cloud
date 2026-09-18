package com.kitex.mom.qms.dto.req;

import lombok.Data;

@Data
public class QmsBadHandleReq {

    private String requestId;

    private Long detailId;
    private Long mrbProposalId;

    private String handleResult;

    private String handleRemark;

    private String handleFile;
    private Boolean mesConcessionUnrestricted;

    private String taskType;

    private java.time.LocalDateTime expectedCompletionTime;

}
