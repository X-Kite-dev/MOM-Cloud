package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/**
 * 外协订单DTO
 *
 * @author kitex
 * @date 2025-04-16 15:23:15
 */
@Data
@Schema(description = "外协订单DTO")
public class PlanOutOrderQueryDTO {

    /**
     * 外协订单号
     */
    @Schema(description = "外协订单号")
    private String outOrderNo;

    /**
     * 外协时间
     */
    @Schema(description = "外协时间")
    private LocalDate[] outDate;

    /**
     * 需求时间
     */
    @Schema(description = "需求时间")
    private LocalDate[] orderDate;
}