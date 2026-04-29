package com.kitex.mom.plan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 主生产计划VO
 *
 * @author kitex
 * @date 2025-02-25 16:17:44
 */
@Data
@Schema(description = "主生产计划VO")
public class MonthlyProductionPlanVO {

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String productCode;

    /**
     * 产品名称
     */
    @Schema(description = "产品名称")
    private String productName;

    /**
     * 月度计划
     */
    @Schema(description = "月度计划")
    private BigDecimal totalPlan;
}
