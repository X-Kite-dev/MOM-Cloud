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
    @Schema(description = "工单排序求解器：GREEDY_WINDOW或BEAM_WINDOW；不是外部有限产能求解器")
    private String sequenceSolverCode;

    @Schema(description = "触发方式: AUTO/MANUAL")
    private String triggerType;

    @Schema(description = "计划周期: DAY/WEEK/MONTH")
    private String cycleType;

    @Schema(description = "计划日期")
    private LocalDate scheduleDate;

    @Schema(description = "T+N排产天数")
    private Integer planNum;

    @Schema(description = "排产目标ID列表")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(contentUsing = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private List<Long> targetIds;
}
