package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

@Data
@Schema(description = "月需求平衡参数")
public class PlnDemandMonthBalanceDTO {

    @Schema(description = "需求月份（yyyy-MM-01）")
    private LocalDate time;

    @Schema(description = "版本号")
    private String versionNo;

    @Schema(description = "是否设置为生效版本")
    private Boolean effectiveFlag;
}
