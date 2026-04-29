package com.kitex.mom.pms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 采购订单新增DTO
 *
 * @author kitex
 * @date 2025-01-07 15:02:11
 */
@Data
@Schema(description = "采购订单新增DTO")
public class PmsOrderAddDTO {

    /**
     * 采购订单id
     */
    @Schema(description = "采购订单id")
    private Long id;

    /**
     * 采购订单号
     */
    @Schema(description = "采购订单号")
    private String pmsOrderNo;

    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 状态（0 未收货 1 收货中 2 完成收货）
     */
    @Schema(description = "状态（0 未收货 1 收货中 2 完成收货）")
    private String status;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;

    /**
     * 类型 false 采购计划生成 true 采购订单新增
     */
    @Schema(description = "类型 false 采购计划生成 true 采购订单新增")
    private Boolean type;

    /**
     * 工厂编码
     */
    @Schema(description = "采购订单明细列表")
    private List<PmsOrderDetailAddDTO> pmsOrderDetailList;
}