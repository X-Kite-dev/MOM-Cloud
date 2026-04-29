package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备到货质检表
 *
 * @author kitex
 * @date 2025-03-26 10:21:32
 */
@Data
@TenantTable
@TableName("tpm_device_arrival_inspection")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "设备到货质检表")
public class TpmDeviceArrivalInspectionEntity extends BaseEntity<TpmDeviceArrivalInspectionEntity> {


    /**
     * 主键
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键")
    private Long id;

    /**
     * 质检单据编码
     */
    @Schema(description = "质检单据编码")
    private String qcCode;

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
     * 驳回原因
     */
    @Schema(description = "驳回原因")
    private String rejectReason;

    /**
     * 质检状态（0待质检 1质检通过 2质检驳回）
     */
    @Schema(description = "质检状态（0待质检 1质检通过 2质检驳回）")
    private String state;

    /**
     * 质检人编码
     */
    @Schema(description = "质检人编码")
    private String qcPersonCode;

    /**
     * 质检人员
     */
    @Schema(description = "质检人员")
    private String qcPersonName;

    /**
     * 质检时间
     */
    @Schema(description = "质检时间")
    private LocalDateTime qcTime;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;


    @TableField(exist = false)
    @Schema(description = "详情")
    private List<TpmDeviceArrivalInspectionDetailsEntity> detailsList;

    @TableField(exist = false)
    @Schema(description = "审批结果")
    private String type;
}