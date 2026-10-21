package com.kitex.mom.qms.dto.req;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QmsCapaCreateReq {
    private String requestId;
    private Long sourceBadId;
    private String title;
    private String problemStatement;
    private String impactScope;
    private String triggerReason;
    private Integer severity;
    private Integer occurrence;
    private Integer detection;
    private String teamMembers;
    private String coordinatorCode;
    private String coordinatorName;
    private LocalDateTime dueTime;
}
