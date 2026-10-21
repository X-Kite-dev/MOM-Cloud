package com.kitex.mom.plan.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class PlanMrpImpactedOperationDTO {
    private Long schedulingTaskId;
    private Long issueId;
    private String issueNo;
    private String routeCode;
    private Integer operationSeq;
    private String processCode;
    private String processName;
    private String resourceType;
    private String resourceCode;
    private String resourceName;
    private LocalDateTime scheduledStartTime;
    private LocalDateTime scheduledEndTime;
    private Boolean lockStatus;
    private String scheduleStatus;
}
