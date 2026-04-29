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
import java.time.LocalDateTime;

/**
 * 价格库
 *
 * @author kitex
 * @date 2025-03-24 17:39:49
 */
@Data
@TenantTable
@TableName("pms_price")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "价格库")
public class PmsPriceEntity extends BaseEntity<PmsPriceEntity> {

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
     * 单位
     */
    @Schema(description = "单位")
    private String matUnit;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 不含税单价
     */
    @Schema(description = "不含税单价")
    private BigDecimal unitPriceWithoutTax;

    /**
     * 税率值
     */
    @Schema(description = "税率值")
    private BigDecimal taxRate;

    /**
     * 含税单价
     */
    @Schema(description = "含税单价")
    private BigDecimal unitPriceWithTax;

    /**
     * 生效时间
     */
    @Schema(description = "生效时间")
    private LocalDateTime effectiveTime;

    /**
     * 失效时间
     */
    @Schema(description = "失效时间")
    private LocalDateTime expirationTime;

    /**
     * 价格来源
     */
    @Schema(description = "价格来源")
    private String priceSource;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 状态
     */
    @Schema(description = "状态")
    private Boolean status;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}