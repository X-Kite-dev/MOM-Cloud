package com.kitex.mom.mes.dto;

import lombok.Data;

@Data
public class MesMaterialCompensationCommandDTO {
    private Long issueId;
    private Long compensationId;
    private String reason;
}
