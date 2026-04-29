package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 计划月需求表
 *
 * @author kitex
 * @date 2025-02-10 09:08:44
 */
@Data
@TenantTable
@TableName("pln_demand_month")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "计划月需求表")
public class PlnDemandMonthEntity extends BaseEntity<PlnDemandMonthEntity> {

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
     * 产品属性
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
     * 需求时间
     */
    @Schema(description = "需求时间")
    private LocalDate demandTime;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 需求来源类型（FORECAST/CONFIRMED）
     */
    @Schema(description = "需求来源类型（FORECAST/CONFIRMED）")
    private String sourceType;

    /**
     * 版本号
     */
    @Schema(description = "版本号")
    private String versionNo;

    /**
     * 是否生效版本
     */
    @Schema(description = "是否生效版本")
    private Boolean effectiveFlag;

    /**
     * 是否冻结
     */
    @Schema(description = "是否冻结")
    private Boolean frozenFlag;

    /**
     * 可执行需求量
     */
    @Schema(description = "可执行需求量")
    private BigDecimal executableNum;
}