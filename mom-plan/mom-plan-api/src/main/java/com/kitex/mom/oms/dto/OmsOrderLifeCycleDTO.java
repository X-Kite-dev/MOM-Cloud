package com.kitex.mom.oms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 订单生命周期DTO
 *
 * @author kitex
 * @date 2025-04-18 17:35:48
 */
@Data
@Schema(description = "订单生命周期DTO")
public class OmsOrderLifeCycleDTO {
    /**
     * 下达号
     */
    @Schema(description = "下达号")
    private String no;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String productCode;

    /**
     * 操作类型
     */
    @Schema(description = "订单操作类型")
    private String type;

    /**
     * 订单处理状态
     */
    @Schema(description = "订单处理状态")
    private String status;

    /**
     * 下达任务状态
     */
    @Schema(description = "下达任务状态")
    private String issueStatus;

    /**
     * 操作内容
     */
    @Schema(description = "操作内容")
    private String content;

    /**
     * 完成数量
     */
    @Schema(description = "完成数量")
    private BigDecimal finishNum;

    /**
     * 完成时间
     */
    @Schema(description = "完成时间")
    private LocalDate finishTime;
}