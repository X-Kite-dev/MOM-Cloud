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
 * MRP运算记录表
 *
 * @author kitex
 * @date 2025-04-15 17:37:58
 */
@Data
@TenantTable
@TableName("plan_mat_mrp")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "MRP运算记录表")
public class PlanMatMrpEntity extends BaseEntity<PlanMatMrpEntity> {

    /**
     * 客户订单号
     */
    @Schema(description = "客户订单号")
    private String planDemandNo;

    /**
     * 销售单号
     */
    @Schema(description = "销售单号")
    private String saleOrderCode;

    /**
     * bom ID
     */
    @Schema(description = "bom ID")
    private Long bomId;

    /**
     * 子级bom ID
     */
    @Schema(description = "子级bom ID")
    private Long subitemBomId;

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
     * 物料规格
     */
    @Schema(description = "物料规格")
    private String matSpec;

    /**
     * 物料单位
     */
    @Schema(description = "物料单位")
    private String matUnit;

    /**
     * 提前期
     */
    @Schema(description = "提前期")
    private Integer leadTime;

    /**
     * 安全库存
     */
    @Schema(description = "安全库存")
    private Integer safetyStock;

    /**
     * 最小订购量
     */
    @Schema(description = "最小订购量")
    private Integer minOrderQuantity;

    /**
     * 固定批量
     */
    @Schema(description = "固定批量")
    private Integer lotSize;

    /**
     * 时间
     */
    @Schema(description = "时间")
    private LocalDate orderDate;

    /**
     * 需求数量
     */
    @Schema(description = "需求数量")
    private BigDecimal num;

    /**
     * 完成数量
     */
    @Schema(description = "完成数量")
    private BigDecimal completeNum;

    /**
     * 类型，采购、自制、委外
     */
    @Schema(description = "类型，采购、自制、委外")
    private String type;

    /**
     * 状态，1已下发、0未下发
     */
    @Schema(description = "状态，1已下发、0未下发")
    private Boolean status;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}