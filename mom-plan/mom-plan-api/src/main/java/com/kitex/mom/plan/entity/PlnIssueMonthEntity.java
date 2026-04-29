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
import java.time.LocalDateTime;

/**
 * 月计划下达表
 *
 * @author kitex
 * @date 2025-02-10 16:11:29
 */
@Data
@TenantTable
@TableName("pln_issue_month")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "月计划下达表")
public class PlnIssueMonthEntity extends BaseEntity<PlnIssueMonthEntity> {

    /**
     * 采购计划下达号
     */
    @Schema(description = "采购计划下达号")
    private String issueMonthNo;

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
     * 数量
     */
    @Schema(description = "数量")
    private BigDecimal num;

    /**
     * 下达时间
     */
    @Schema(description = "下达时间")
    private LocalDate issueTime;

    /**
     * 需求时间
     */
    @Schema(description = "需求时间")
    private LocalDate demandTime;

    /**
     * 计划类型
     */
    @Schema(description = "计划类型")
    private String planType;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 状态, 0:未下发, 1:已下发, 2: 采购中, 3: 已完成, 4:已关闭
     */
    @Schema(description = "状态, 0:未下发, 1:已下发, 2: 采购中, 3: 已完成, 4:已关闭")
    private String status;

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

    /**
     * 来源类型
     */
    @Schema(description = "来源类型")
    private String type;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}