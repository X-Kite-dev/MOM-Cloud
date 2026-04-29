package com.kitex.mom.plan.vo;

import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 计划月需求表vo
 *
 * @author kitex
 * @date 2025-02-10 13:53:31
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "计划月需求表vo")
public class PlnDemandMonthVO extends BaseEntity<PlnDemandMonthVO> {

    /**
     * 客户编码
     */
    @Schema(description = "客户编码")
    private String customerCode;

    /**
     * 客户名称
     */
    @Schema(description = "客户名称")
    private String customerName;

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
    private String specificationModel;

    /**
     * 物料属性
     */
    @Schema(description = "产品属性")
    private String materialAttribute;

    /**
     * 总需求量
     */
    @Schema(description = "总需求量")
    private BigDecimal totalDemandNum;

    /**
     * 可用量
     */
    @Schema(description = "可用量")
    private BigDecimal availableNum;

    /**
     * 总缺料数量
     */
    @Schema(description = "总缺料数量")
    private BigDecimal totalLackNum;

    /**
     * 月缺量
     */
    @Schema(description = "月缺量")
    private BigDecimal monthDeficitNum;

    /**
     * 近三月用量
     */
    @Schema(description = "近三月用量")
    private BigDecimal threeMonthNum;

    /**
     * 单月平均用量
     */
    @Schema(description = "单月平均用量")
    private BigDecimal monthAverageNum;

    /**
     * 整箱数量
     */
    @Schema(description = "整箱数量")
    private BigDecimal containerNum;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 需求时间
     */
    @Schema(description = "需求时间")
    private LocalDate demandTime;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    private String factoryCode;
}