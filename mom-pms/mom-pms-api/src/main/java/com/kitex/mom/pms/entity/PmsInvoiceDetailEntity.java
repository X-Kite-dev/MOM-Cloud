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
 * 采购发票
 *
 * @author kitex
 * @date 2025-04-07 17:53:31
 */
@Data
@TableName("pms_invoice_detail")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "pms_invoice_detail")
public class PmsInvoiceDetailEntity extends Model<PmsInvoiceDetailEntity> {

    /**
     * 发票单明细id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "发票单明细id")
    private Long id;
    /**
     * 发票单id
     */
    @Schema(description = "发票单id")
    private Long invoiceId;
    /**
     * 项目名称
     */
    @Schema(description = "项目名称")
    private String name;
    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String spec;
    /**
     * 单位
     */
    @Schema(description = "单位")
    private String unit;
    /**
     * 数量
     */
    @Schema(description = "数量")
    private Integer num;
    /**
     * 单价
     */
    @Schema(description = "单价")
    private BigDecimal price;
    /**
     * 金额
     */
    @Schema(description = "金额")
    private BigDecimal taxExclusiveAmount;
    /**
     * 税率
     */
    @Schema(description = "税率")
    private BigDecimal taxRate;
    /**
     * 税额
     */
    @Schema(description = "税额")
    private BigDecimal taxAmount;
}