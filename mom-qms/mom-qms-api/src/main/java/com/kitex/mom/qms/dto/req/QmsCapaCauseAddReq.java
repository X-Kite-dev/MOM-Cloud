package com.kitex.mom.qms.dto.req;

import lombok.Data;

@Data
public class QmsCapaCauseAddReq {
    private String requestId;
    private Long capaId;
    private String causeType;
    private String analysisMethod;
    private String causeDescription;
    private String evidence;
}
