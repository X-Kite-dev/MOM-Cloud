package com.kitex.mom.mes.dto;

import lombok.Data;

@Data
public class AndonRecordVerifyVO {

    private Long id;
    private String requestId;
    private String verificationResult;
    private String verificationRemark;
}
