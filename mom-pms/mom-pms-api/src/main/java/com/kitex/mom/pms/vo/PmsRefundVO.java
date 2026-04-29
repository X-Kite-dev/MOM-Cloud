package com.kitex.mom.pms.vo;

import com.kitex.common.core.entity.BaseEntity;
import com.kitex.mom.pms.entity.PmsPaymentInvoiceEntity;
import com.kitex.mom.pms.entity.PmsPaymentReconciliationEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 退款DTO
 *
 * @author kitex
 * @date 2025-04-08 17:24:20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "退款DTO")
public class PmsRefundVO extends BaseEntity<PmsRefundVO> {

    /**
     * 付款单号
     */
    @Schema(description = "付款单号")
    private String paymentNo;

    /**
     * 退款单号
     */
    @Schema(description = "退款单号")
    private String refundNo;

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
     * 退款方式
     */
    @Schema(description = "退款方式")
    private String refundMethod;

    /**
     * 退款日期
     */
    @Schema(description = "退款日期")
    private LocalDate refundDate;

    /**
     * 退款金额
     */
    @Schema(description = "退款金额")
    private BigDecimal refundAmount;

    /**
     * 付款凭证
     */
    @Schema(description = "付款凭证")
    private String files;

    /**
     * 附件
     */
    @Schema(description = "附件")
    private String refundFiles;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 0 预付款 1 票到付款
     */
    @Schema(description = "0 预付款 1 票到付款")
    private String type;

    /**
     * 工厂编码ID
     */
    @Schema(description = "工厂编码ID")
    private String factoryCode;

    /**
     * 付款-发票列表
     */
    @Schema(description = "付款-发票列表")
    private List<PmsPaymentInvoiceEntity> pmsPaymentInvoiceList;

    /**
     * 付款-业务对账列表
     */
    @Schema(description = "付款-业务对账列表")
    private List<PmsPaymentReconciliationEntity> pmsPaymentReconciliationList;
}