package com.kitex.mom.wms.dto;

import lombok.Data;

@Data
public class WmsMesIssueCommand {
    private Long outboxId;
    private Long wmsBillId;
    private String requestId;
    private String reason;
}
