package com.kitex.mom.plan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "月需求准备状态")
public class PlnDemandMonthReadinessVO {

    @Schema(description = "是否已完成月需求平衡")
    private Boolean balanced;

    @Schema(description = "是否已完成月需求下推")
    private Boolean dispatched;
}
