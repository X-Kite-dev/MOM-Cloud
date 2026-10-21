package com.kitex.mom.qms.dto.req;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QmsCapaActionAddReq {
    private String requestId;
    private Long capaId;
    private String actionType;
    private String title;
    private String description;
    private String ownerCode;
    private String ownerName;
    private LocalDateTime dueTime;
}
