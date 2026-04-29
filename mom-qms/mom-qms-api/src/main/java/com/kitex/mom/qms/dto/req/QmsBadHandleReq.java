package com.kitex.mom.qms.dto.req;

import lombok.Data;

@Data
public class QmsBadHandleReq {

    private Long detailId;

    private String handleResult;

    private String handleRemark;

    private String handleFile;

    private String taskType;

}
