package com.kitex.mom.oms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 订单总览DTO
 *
 * @author kitex
 * @date 2025-04-14 10:46:04
 */
@Data
public class OmsOrderOverviewDto {

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
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String productSpec;

    /**
     * 库存数量
     */
    @Schema(description = "库存数量")
    private BigDecimal liveStockNum;

    /**
     * 月需求数量
     */
    @Schema(description = "月需求数量")
    private BigDecimal deliverNum;

    /**
     * 月需求时间
     */
    @Schema(description = "月需求时间")
    private LocalDate date;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    private String factoryCode;

}