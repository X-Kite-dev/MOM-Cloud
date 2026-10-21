package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "APS资源日负荷查询")
public class PlnResourceLoadQueryDTO {

    @Schema(description = "排产任务ID；为空时仅统计已正式锁定工序")
    private Long taskId;

    @Schema(description = "开始日期，默认当天")
    private LocalDate startDate;

    @Schema(description = "结束日期，默认开始日期后6天，最长93天")
    private LocalDate endDate;

    @Schema(description = "STATION、LINE、WORKSHOP、OUTSOURCE、DEVICE、EMPLOYEE或TOOLING")
    private String resourceType;

    @Schema(description = "资源编码，支持精确过滤")
    private String resourceCode;

    @Schema(description = "NORMAL、HIGH、SATURATED或OVERLOAD")
    private String loadStatus;
}
