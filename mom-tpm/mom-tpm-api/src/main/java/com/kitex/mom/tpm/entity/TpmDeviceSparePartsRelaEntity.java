package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * TPM设备-备件关联表
 *
 * @author kitex
 * @date 2025-03-18 10:00:49
 */
@Data
@TableName("tpm_device_spare_parts_rela")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "TPM设备-备件关联表")
public class TpmDeviceSparePartsRelaEntity extends Model<TpmDeviceSparePartsRelaEntity> {


    /**
     * 主键ID，自增唯一标识
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID，自增唯一标识")
    private Long id;

    /**
     * 设备编码，关联设备表唯一标识
     */
    @Schema(description = "设备编码，关联设备表唯一标识")
    private String deviceCode;

    /**
     * 备件编码，关联备件表唯一标识
     */
    @Schema(description = "备件编码，关联备件表唯一标识")
    private String sparePartCode;

    /**
     * 备件名称
     */
    @TableField(exist = false)
    @Schema(description = "备件名称")
    private String sparePartName;

    /**
     * 安装日期，记录备件装配到设备的日期
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "安装日期，记录备件装配到设备的日期")
    private LocalDate installationDate;

    /**
     * 更换周期（单位：天），建议更换时间间隔
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    @Schema(description = "更换周期（单位：天），建议更换时间间隔")
    private Integer replacementInterval;

    @TableField(exist = false)
    @Schema(description = "数量")
    private BigDecimal inventoryNum;

    @TableField(exist = false)
    @Schema(description = "最小安全库存")
    private BigDecimal safetyStockMin;


}