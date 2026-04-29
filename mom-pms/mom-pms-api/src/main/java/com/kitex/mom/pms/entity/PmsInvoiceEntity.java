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
 * 采购发票
 *
 * @author kitex
 * @date 2025-04-07 17:53:31
 */
@Data
@TenantTable
@TableName("pms_invoice")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "采购发票")
public class PmsInvoiceEntity extends BaseEntity<PmsInvoiceEntity> {

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
     * 购买方纳税人识别号
     */
    @Schema(description = "购买方纳税人识别号")
    private String buyerTaxId;

    /**
     * 购买方地址
     */
    @Schema(description = "购买方地址")
    private String buyerAddress;

    /**
     * 购买方开户行
     */
    @Schema(description = "购买方开户行")
    private String buyerBank;

    /**
     * 购买方电话
     */
    @Schema(description = "购买方电话")
    private String buyerPhone;

    /**
     * 购买方开户行账号
     */
    @Schema(description = "购买方开户行账号")
    private String buyerAccount;

    /**
     * 销售方
     */
    @Schema(description = "销售方")
    private String seller;

    /**
     * 销售方纳税人识别号
     */
    @Schema(description = "销售方纳税人识别号")
    private String sellerTaxId;

    /**
     * 销售方地址
     */
    @Schema(description = "销售方地址")
    private String sellerAddress;

    /**
     * 销售方电话
     */
    @Schema(description = "销售方电话")
    private String sellerPhone;

    /**
     * 销售方开户行
     */
    @Schema(description = "销售方开户行")
    private String sellerBank;

    /**
     * 销售方开户行账号
     */
    @Schema(description = "销售方开户行账号")
    private String sellerAccount;

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

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 收款人
     */
    @Schema(description = "收款人")
    private String payeeUser;

    /**
     * 复核人
     */
    @Schema(description = "复核人")
    private String recheckUser;

    /**
     * 开票人
     */
    @Schema(description = "开票人")
    private String invocieUser;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 对账单列表
     */
    @Schema(description = "对账单列表")
    @ExcelIgnore
    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "invoiceId")
    private List<PmsInvoiceReconciliationEntity> pmsReconciliationDetailList;

    /**
     * 发票明细列表
     */
    @Schema(description = "发票明细列表")
    @ExcelIgnore
    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "invoiceId")
    private List<PmsInvoiceDetailEntity> pmsInvoiceDetailList;
}