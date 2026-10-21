package com.kitex.mom.qms.dto.req;

import lombok.Data;

@Data
public class QmsCapaEffectivenessReq {
    private String requestId;
    private Long capaId;
    private String result;
    private String remark;
    private String evidence;
    private String lessonsLearned;
}
