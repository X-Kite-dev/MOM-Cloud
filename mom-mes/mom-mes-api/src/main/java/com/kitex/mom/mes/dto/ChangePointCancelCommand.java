package com.kitex.mom.mes.dto;

import lombok.Data;

@Data
public class ChangePointCancelCommand {
    private Long id;
    private String requestId;
    private String reason;
}
