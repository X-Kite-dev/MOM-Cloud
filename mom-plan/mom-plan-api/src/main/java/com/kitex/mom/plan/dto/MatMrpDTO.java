package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 物料基本信息MRP DTO
 *
 * @author kitex
 * @date 2025-04-15 15:07:10
 */
@Data
@Schema(description = "物料基本信息MRP DTO")
public class MatMrpDTO {

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String materialName;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String materialCode;

    /**
     * 物料属性
     */
    @Schema(description = "物料属性")
    private String materialAttribute;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String specificationModel;

    /**
     * 基本单位
     */
    @Schema(description = "基本单位")
    private String basicUnit;

    /**
     * 固定提前期
     */
    @Schema(description = "固定提前期")
    private BigDecimal fixedLeadTime;

    /**
     * 发料批量
     */
    @Schema(description = "发料批量")
    private BigDecimal issuanceBatch;

    /**
     * 最小订购量
     */
    @Schema(description = "最小订购量")
    private Integer minOrderQuantity;

    /**
     * 安全库存
     */
    @Schema(description = "最小安全库存")
    private BigDecimal minStock;

    /**
     * 现有库存
     */
    @Schema(description = "现有库存")
    private Double stock;
}