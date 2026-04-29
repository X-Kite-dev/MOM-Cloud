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
 * 历史采购价表
 *
 * @author kitex
 * @date 2025-03-25 11:05:14
 */
@Data
@TenantTable
@TableName("pms_history_price")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "历史采购价表")
public class PmsHistoryPriceEntity extends BaseEntity<PmsHistoryPriceEntity> {

    /**
     * 采购订单号
     */
    @Schema(description = "采购订单号")
    private String pmsOrderNo;

    /**
     * 采购订单明细ID
     */
    @Schema(description = "采购订单明细ID")
    private Long pmsOrderDetailId;

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
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}