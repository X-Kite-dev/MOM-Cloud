package com.kitex.mom.plan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "APS资源负荷关联工序")
public class PlnResourceLoadOperationVO {

    private Long operationId;
    private Long taskId;
    private Long issueId;
    private String issueNo;
    private String productCode;
    private String routeCode;
    private Integer operationSeq;
    private String processCode;
    private String processName;
    private String resourceCode;
    private String resourceName;
    private Integer resourceSlotNo;
    private String resourceGroupCode;
    private String resourceGroupName;
    private Integer resourceGroupCapacity;
    private Integer resourceGroupSlotNo;
    private String deviceCode;
    private String employeeCode;
    private String toolingCodes;
    private Long activeMinutes;
    private LocalDateTime scheduledStartTime;
    private LocalDateTime scheduledEndTime;
    private String scheduleStatus;
    private Boolean lockStatus;
    private String constraintSummary;
}
