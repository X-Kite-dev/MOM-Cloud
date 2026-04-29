package com.kitex.mom.tpm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class MaintenanceStatusDto {

    @Schema(description = "总任务")
    private Integer totalTasks;

    @Schema(description = "完成数量")
    private Integer completedTasks;

    @Schema(description = "及时完成数量")
    private Integer timelyTasks;

    @Schema(description = "完成率")
    private BigDecimal completionRate;

    @Schema(description = "及时率")
    private BigDecimal timelinessRate;

}
