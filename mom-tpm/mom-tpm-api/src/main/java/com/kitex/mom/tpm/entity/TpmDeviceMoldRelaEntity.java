package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * TPM设备-模具关联表
 *
 * @author kitex
 * @date 2025-03-18 10:00:49
 */
@Data
@TableName("tpm_device_mold_rela")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "TPM设备-模具关联表")
public class TpmDeviceMoldRelaEntity extends Model<TpmDeviceMoldRelaEntity> {


    /**
     * 主键ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    /**
     * 设备编码
     */
    @Schema(description = "设备编码")
    private String deviceCode;

    /**
     * 模具编码
     */
    @Schema(description = "模具编码")
    private String moldCode;

    @TableField(exist = false)
    @Schema(description = "模具名称")
    private String moldName;

    @TableField(exist = false)
    @Schema(description = "规格")
    private String spec;

    @TableField(exist = false)
    @Schema(description = "型号")
    private String model;

    @TableField(exist = false)
    @Schema(description = "设备分类编码")
    private String classCode;

    @TableField(exist = false)
    @Schema(description = "设备分类名称")
    private String className;

    @TableField(exist = false)
    @Schema(description = "设备位置")
    private String location;


}