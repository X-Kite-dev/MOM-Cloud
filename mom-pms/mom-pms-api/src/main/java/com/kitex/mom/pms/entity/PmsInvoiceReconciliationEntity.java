package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 发票-业务对账表
 *
 * @author kitex
 * @date 2025-04-08 09:53:21
 */
@Data
@TableName("pms_invoice_reconciliation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "发票-业务对账表")
public class PmsInvoiceReconciliationEntity extends Model<PmsInvoiceReconciliationEntity> {

    /**
     * 发票-业务对账表id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "发票-业务对账表id")
    private Long id;

    /**
     * 发票单id
     */
    @Schema(description = "发票单id")
    private Long invoiceId;

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
     * 数量
     */
    @Schema(description = "数量")
    private Double arrivalNum;

    /**
     * 单价
     */
    @Schema(description = "单价")
    private BigDecimal price;

    /**
     * 金额
     */
    @Schema(description = "金额")
    private BigDecimal actuallyAmount;
}