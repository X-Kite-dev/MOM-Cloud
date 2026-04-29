package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "月需求下推参数")
public class PlnDemandMonthDispatchDTO {

    @Schema(description = "需求月份（yyyy-MM-01）")
    private LocalDate time;

    @Schema(description = "版本号")
    private String versionNo;
}
