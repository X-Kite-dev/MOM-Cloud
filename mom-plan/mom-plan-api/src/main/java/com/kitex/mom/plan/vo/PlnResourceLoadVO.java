package com.kitex.mom.plan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Schema(description = "APS资源日负荷")
public class PlnResourceLoadVO {

    private String resourceType;
    private String resourceCode;
    private String resourceName;
    private LocalDate loadDate;

    @Schema(description = "资源容量单元；工位为并行槽位数，其他独占资源为1")
    private Integer capacityUnits;
    private Long availableMinutes;
    private Long plannedMinutes;
    private Long idleMinutes;
    private Long overloadMinutes;

    @Schema(description = "负荷率百分比")
    private BigDecimal utilizationRate;

    @Schema(description = "NORMAL、HIGH、SATURATED或OVERLOAD")
    private String loadStatus;

    @Schema(description = "容量是否由当前日历及资源快照完整核验")
    private Boolean capacityVerified;
    private String capacityExplanation;

    private Integer operationCount;
    private Integer issueCount;
    private Integer taskCount;
    private LocalDateTime firstStartTime;
    private LocalDateTime lastEndTime;
}
