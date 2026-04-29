package com.kitex.mom.plan.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 计划下达VO
 *
 * @author kitex
 * @date 2025-02-06 14:16:12
 */
@Data
@Schema(description = "计划下达VO")
public class PlnIssueVO {

    /**
     * 计划下达主键
     */
    @Schema(description = "计划下达主键")
    private Long issueId;

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
     * 数量
     */
    @Schema(description = "数量")
    private BigDecimal num;

    /**
     * 下达时间
     */
    @Schema(description = "下达时间")
    private LocalDateTime issueTime;

    /**
     * 需求时间
     */
    @Schema(description = "需求时间")
    private LocalDate demandTime;

    /**
     * 所属模块
     */
    @Schema(description = "所属模块")
    private String module;

    /**
     * 计划类型
     */
    @Schema(description = "计划类型")
    private String planType;

    /**
     * 来源类型
     */
    @Schema(description = "来源类型")
    private String type;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;
}