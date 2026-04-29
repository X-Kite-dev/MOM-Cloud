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
 * 产品需求表
 *
 * @author kitex
 * @date 2024-11-06 17:36:16
 */
@Data
@TenantTable
@TableName("pln_product_demand")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "产品需求表")
public class PlnProductDemandEntity extends BaseEntity<PlnProductDemandEntity> {

    /**
     * 客户订单号
     */
    @Schema(description = "客户订单号")
    private String demandNo;

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
     * 销售单号
     */
    @Schema(description = "销售单号")
    private String saleOrderCode;

    /**
     * 销售单号明细id
     */
    @Schema(description = "销售单号明细id")
    private Long saleOrderCodeDetails;

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
     * 产品规格
     */
    @Schema(description = "产品规格")
    private String specification;

    /**
     * 产品单位
     */
    @Schema(description = "产品单位")
    private String productUnit;

    /**
     * 需求时间
     */
    @Schema(description = "需求时间")
    private LocalDate planEndDate;

    /**
     * 需求数量
     */
    @Schema(description = "需求数量")
    private BigDecimal needNum;

    /**
     * 完成数量
     */
    @Schema(description = "完成数量")
    private BigDecimal completeNum;

    /**
     * 计划状态 - 0: 未接受, 1: 已接收  2 已生成mrp
     */
    @Schema(description = "计划状态 - 0: 未接受, 1: 已接收 2 已生成mrp")
    private String planState;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}