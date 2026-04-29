package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 业务对账
 *
 * @author kitex
 * @date 2025-04-07 14:53:53
 */
@Data
@TenantTable
@TableName("pms_business_reconciliation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "业务对账")
public class PmsBusinessReconciliationEntity extends BaseEntity<PmsBusinessReconciliationEntity> {

    /**
     * 对账单号
     */
    @Schema(description = "对账单号")
    private String reconciliationNo;

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
     * 物料单位
     */
    @Schema(description = "物料单位")
    private String matUnit;

    /**
     * 物料规格
     */
    @Schema(description = "物料规格")
    private String matSpec;

    /**
     * 到货数量
     */
    @Schema(description = "到货数量")
    private Double arrivalNum;

    /**
     * 单价
     */
    @Schema(description = "单价")
    private BigDecimal price;

    /**
     * 应对金额
     */
    @Schema(description = "应对金额")
    private BigDecimal reconciliationAmount;

    /**
     * 实对金额
     */
    @Schema(description = "实对金额")
    private BigDecimal actuallyAmount;

    /**
     * 对账确认状态, true 已确认 false 未确认
     */
    @Schema(description = "对账确认状态, true 已确认 false 未确认")
    private Boolean status;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}