package com.kitex.mom.plan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;


/**
 * T+N计划需求日期VO
 *
 * @author kitex
 * @date 2025-02-05 16:12:25
 */
@Data
@Schema(description = "T+N计划需求日期VO")
public class PlnTemporaryDemandDateVO {

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String productCode;

    /**
     * 数量
     */
    @Schema(description = "数量")
    private BigDecimal num;

    /**
     * 日期
     */
    @Schema(description = "日期")
    private String monthDay;

}