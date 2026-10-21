package com.kitex.mom.mes.dto;

import lombok.Data;

@Data
public class MesAutoReportRetryDTO {
    private Long eventId;
    private String reason;
}
