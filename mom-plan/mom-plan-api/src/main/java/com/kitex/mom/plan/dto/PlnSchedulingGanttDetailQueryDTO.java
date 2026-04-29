package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "排产甘特明细查询参数")
public class PlnSchedulingGanttDetailQueryDTO {

    @Schema(description = "任务ID")
    private Long taskId;

    @Schema(description = "任务编号")
    private String taskCode;

    @Schema(description = "工艺路线编号")
    private String routeCode;
}
