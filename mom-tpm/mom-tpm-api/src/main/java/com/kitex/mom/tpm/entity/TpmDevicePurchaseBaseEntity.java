package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 设备采购单表
 *
 * @author kitex
 * @date 2025-03-26 10:13:32
 */
@Data
@TenantTable
@TableName("tpm_device_purchase_base")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "设备采购单表")
public class TpmDevicePurchaseBaseEntity extends BaseEntity<TpmDevicePurchaseBaseEntity> {


    /**
     * 主键
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private Long id;

    /**
     * 采购单号
     */
    @Schema(description = "采购单号")
    private String purchaseCode;

    /**
     * 采购名称
     */
    @Schema(description = "采购名称")
    private String purchaseName;

    /**
     * 采购时间
     */
    @Schema(description = "采购时间")
    private LocalDate purchaseTime;

    /**
     * 采购人编码
     */
    @Schema(description = "采购人编码")
    private String purchasePersonCode;

    /**
     * 采购人
     */
    @Schema(description = "采购人")
    private String purchasePersonName;

    /**
     * 采购设备（逗号分隔）
     */
    @Schema(description = "采购设备（逗号分隔）")
    private String purchaseDevice;

    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商
     */
    @Schema(description = "供应商")
    private String supplierName;

    /**
     * 含税金额
     */
    @Schema(description = "含税金额")
    private BigDecimal allMoney;

    /**
     * 不含税金额
     */
    @Schema(description = "不含税金额")
    private BigDecimal taxRateMoney;

    /**
     * 税率
     */
    @Schema(description = "税率")
    private BigDecimal taxRate;

    /**
     * 生产质检单状态（0未生成 1已生成）
     */
    @Schema(description = "生产质检单状态（0未生成 1已生成）")
    private String state;

    /**
     * 采购说明
     */
    @Schema(description = "采购说明")
    private String remark;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @TableField(exist = false)
    @Schema(description = "采购明细")
    private List<TpmDevicePurchaseDetailsEntity> detailsList;
}