package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 排产运行参数
 */
@Data
@Schema(description = "排产运行参数")
public class PlnSchedulingRunDTO {

    @Schema(description = "运行模式: simulate/official")
    private String mode;

    @Schema(description = "触发方式: AUTO/MANUAL")
    private String triggerType;

    @Schema(description = "计划周期: DAY/WEEK/MONTH")
    private String cycleType;

    @Schema(description = "计划日期")
    private LocalDate scheduleDate;

    @Schema(description = "T+N排产天数")
    private Integer planNum;

    @Schema(description = "排产目标ID列表")
    private List<Long> targetIds;
}
