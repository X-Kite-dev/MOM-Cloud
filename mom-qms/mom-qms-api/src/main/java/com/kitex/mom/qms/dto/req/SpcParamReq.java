package com.kitex.mom.qms.dto.req;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SpcParamReq {

    private String matCode;


    private String testName;


    private LocalDateTime startTime;

    private LocalDateTime endTime;


}
