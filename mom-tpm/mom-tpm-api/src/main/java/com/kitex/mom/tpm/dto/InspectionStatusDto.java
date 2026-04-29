package com.kitex.mom.tpm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class InspectionStatusDto {

    @Schema(description = "待巡检")
    private Integer pendingCount;

    @Schema(description = "已完成")
    private Integer completed;

    @Schema(description = "进行中")
    private Integer overdueCount;

    @Schema(description = "完成率")
    private BigDecimal completionRate;


}
