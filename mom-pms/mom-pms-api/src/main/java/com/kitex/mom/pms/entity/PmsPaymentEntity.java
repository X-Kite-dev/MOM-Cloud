package com.kitex.mom.pms.entity;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.yulichang.annotation.EntityMapping;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 付款
 *
 * @author kitex
 * @date 2025-04-08 11:08:12
 */
@Data
@TenantTable
@TableName("pms_payment")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "付款")
public class PmsPaymentEntity extends BaseEntity<PmsPaymentEntity> {

    /**
     * 付款单号
     */
    @Schema(description = "付款单号")
    private String paymentNo;

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
     * 供应商开户行
     */
    @Schema(description = "供应商开户行")
    private String supplierBank;

    /**
     * 供应商账号
     */
    @Schema(description = "供应商账号")
    private String supplierAccount;

    /**
     * 付款方式
     */
    @Schema(description = "付款方式")
    private String paymentMethod;

    /**
     * 付款日期
     */
    @Schema(description = "付款日期")
    private LocalDate paymentDate;

    /**
     * 开票金额
     */
    @Schema(description = "开票金额")
    private BigDecimal invoiceAmount;

    /**
     * 已付款金额
     */
    @Schema(description = "已付款金额")
    private BigDecimal paidAmount;

    /**
     * 付款中金额
     */
    @Schema(description = "付款中金额")
    private BigDecimal pendingPaymentAmount;

    /**
     * 可付款金额
     */
    @Schema(description = "可付款金额")
    private BigDecimal availablePaymentAmount;

    /**
     * 退款
     */
    @Schema(description = "退款")
    private BigDecimal refundAmount;

    /**
     * 附件
     */
    @Schema(description = "附件")
    private String files;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 0 待付款 1 已付款
     */
    @Schema(description = "0 待付款 1 已付款")
    private String status;

    /**
     * 0 预付款 1 票到付款
     */
    @Schema(description = "0 预付款 1 票到付款")
    private String type;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 付款-发票列表
     */
    @Schema(description = "付款-发票列表")
    @ExcelIgnore
    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "paymentId")
    private List<PmsPaymentInvoiceEntity> pmsPaymentInvoiceList;

    /**
     * 付款-业务对账列表
     */
    @Schema(description = "付款-业务对账列表")
    @ExcelIgnore
    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "paymentId")
    private List<PmsPaymentReconciliationEntity> pmsPaymentReconciliationList;
}