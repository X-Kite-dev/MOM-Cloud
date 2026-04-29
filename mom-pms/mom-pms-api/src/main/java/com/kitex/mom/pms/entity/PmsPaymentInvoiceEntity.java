package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 付款
 *
 * @author kitex
 * @date 2025-04-08 11:08:12
 */
@Data
@TableName("pms_payment_invoice")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "pms_payment_invoice")
public class PmsPaymentInvoiceEntity extends Model<PmsPaymentInvoiceEntity> {

    /**
     * 付款-发票id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "付款-发票id")
    private Long id;
    /**
     * 预付款id
     */
    @Schema(description = "付款id")
    private Long paymentId;
    /**
     * 发票号码
     */
    @Schema(description = "发票号码")
    private String invoiceCode;
    /**
     * 发票代码
     */
    @Schema(description = "发票代码")
    private String invoiceNo;
    /**
     * 开票日期
     */
    @Schema(description = "开票日期")
    private LocalDate invoiceDate;
    /**
     * 发票类型
     */
    @Schema(description = "发票类型")
    private String invoiceType;
    /**
     * 开票抬头
     */
    @Schema(description = "开票抬头")
    private String invoiceTitle;
    /**
     * 购买方
     */
    @Schema(description = "购买方")
    private String buyer;
    /**
     * 销售方
     */
    @Schema(description = "销售方")
    private String seller;
    /**
     * 合计金额
     */
    @Schema(description = "合计金额")
    private BigDecimal totalAmount;
    /**
     * 合计税额
     */
    @Schema(description = "合计税额")
    private BigDecimal taxAmount;
    /**
     * 价税合计
     */
    @Schema(description = "价税合计")
    private BigDecimal totalAmountWithTax;
}