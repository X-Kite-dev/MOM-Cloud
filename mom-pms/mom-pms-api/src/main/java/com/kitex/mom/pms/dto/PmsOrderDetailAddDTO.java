package com.kitex.mom.pms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 采购订单明细DTO
 *
 * @author kitex
 * @date 2025-01-07 15:03:38
 */
@Data
@Schema(description = "采购订单明细DTO")
public class PmsOrderDetailAddDTO {
    /**
     * 采购订单明细id
     */
    @Schema(description = "采购订单明细id")
    private Long id;

    /**
     * 采购订单id
     */
    @Schema(description = "采购订单id")
    private Long pmsOrderId;

    /**
     * 采购计划号
     */
    @Schema(description = "采购计划号")
    private String pmsPlanNo;

    /**
     * 采购计划下单数量
     */
    @Schema(description = "采购计划下单数量")
    private Double planOrderNum;

    /**
     * 订单号
     */
    @Schema(description = "订单号")
    private String orderNo;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;
    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;
    /**
     * 物料单位
     */
    @Schema(description = "物料单位")
    private String matUnit;
    /**
     * 物料规格
     */
    @Schema(description = "物料规格")
    private String matSpec;
    /**
     * 物料有效期
     */
    @Schema(description = "物料有效期")
    private Integer matExpirationDay;
    /**
     * 要货数量
     */
    @Schema(description = "要货数量")
    private Double orderNum;
    /**
     * 要货时间
     */
    @Schema(description = "要货时间")
    private LocalDate deliveryTime;
    /**
     * 单价
     */
    @Schema(description = "单价")
    private BigDecimal price;
    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;
}