package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.yulichang.annotation.EntityMapping;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 采购订单表
 *
 * @author kitex
 * @date 2025-01-07 13:36:28
 */
@Data
@TenantTable
@TableName("pms_order")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "采购订单表")
public class PmsOrderEntity extends BaseEntity<PmsOrderEntity> {

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
     * 状态（0 未审核 1 审核中 2 审核通过 3 收货中 4 部分到货 5 完成收货 6 驳回）
     */
    @Schema(description = "状态（0 未审核 1 审核中 2 审核通过 3 收货中 4 部分到货 5 完成收货 6 驳回）")
    private String status;

    /**
     * 类型 false 采购计划生成 true 采购订单新增
     */
    @Schema(description = "类型 false 采购计划生成 true 采购订单新增")
    private Boolean type;

    /**
     * 审批意见
     */
    @Schema(description = "审批意见")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String checkReason;

    /**
     * 是否签订合同 true 是 false 否
     */
    @Schema(description = "是否签订合同 true 是 false 否")
    private Boolean contractStatus;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "pmsOrderId")
    private List<PmsOrderDetailEntity> pmsOrderDetailList;
}