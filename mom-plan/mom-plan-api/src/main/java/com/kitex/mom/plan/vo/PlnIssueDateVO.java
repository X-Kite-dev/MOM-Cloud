package com.kitex.mom.plan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;


/**
 * 物料生产计划日期数量VO
 *
 * @author kitex
 * @date 2025-02-07 11:04:21
 */
@Data
@Schema(description = "物料生产计划日期数量VO")
public class PlnIssueDateVO {

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String productCode;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String productName;

    /**
     * 物料属性
     */
    @Schema(description = "物料属性")
    private String materialattribute;

    /**
     * 月计划数量
     */
    @Schema(description = "月计划数量")
    private BigDecimal monthNum;

    /**
     * 日数量
     */
    @Schema(description = "日数量")
    private BigDecimal dayNum;

    /**
     * 日期
     */
    @Schema(description = "日期")
    private String monthDay;

}