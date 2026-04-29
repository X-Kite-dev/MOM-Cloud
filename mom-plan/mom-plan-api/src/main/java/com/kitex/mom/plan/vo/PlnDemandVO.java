package com.kitex.mom.plan.vo;

import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 计划需求表vo
 *
 * @author kitex
 * @date 2025-02-05 16:46:53
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "计划需求表vo")
public class PlnDemandVO extends BaseEntity<PlnDemandVO> {

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
     * 预排需求量
     */
    @Schema(description = "预排需求量")
    private BigDecimal predictDemandNum;

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
     * 已提供比例
     */
    @Schema(description = "已提供比例")
    private String providedRatio;

    /**
     * 近三月用量
     */
    @Schema(description = "近三月用量")
    private BigDecimal threeMonthNum;

    /**
     * 发货
     */
    @Schema(description = "发货")
    private String dailyDispatch;

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