package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.util.List;

/**
 * TPM备件更换审批单据表
 *
 * @author kitex
 * @date 2025-03-18 10:27:11
 */
@Data
@TenantTable
@TableName("tpm_spare_parts_replace")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "TPM备件更换审批单据表")
public class TpmSparePartsReplaceEntity extends BaseEntity<TpmSparePartsReplaceEntity> {


    /**
     * 主键ID，自增唯一标识
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID，自增唯一标识")
    private Long id;

    /**
     * 单据唯一编码
     */
    @Schema(description = "单据唯一编码")
    private String docSn;

    /**
     * 申请号
     */
    @Schema(description = "申请号")
    private String applyCode;

    /**
     * 设备编码
     */
    @Schema(description = "设备编码")
    private String deviceCode;

    /**
     * 设备名称
     */
    @Schema(description = "设备名称")
    private String deviceName;

    /**
     * 备件编码（关联tpm_spare_parts表）
     */
    @Schema(description = "备件编码（关联tpm_spare_parts表）")
    private String sparePartCode;

    /**
     * 备件名称（冗余存储）
     */
    @Schema(description = "备件名称（冗余存储）")
    private String sparePartName;

    /**
     * 备件型号（精确匹配备件规格）
     */
    @Schema(description = "备件型号（精确匹配备件规格）")
    private String model;

    /**
     * 实际更换日期 格式：YYYY-MM-DD）
     */
    @Schema(description = "实际更换日期 格式：YYYY-MM-DD）")
    private LocalDate replaceDate;

    /**
     * 更换原因（枚举值：损坏/寿命到期/预防性维护）
     */
    @Schema(description = "更换原因（枚举值：损坏/寿命到期/预防性维护）")
    private String replaceReason;

    /**
     * 操作人工号（关联HR系统）
     */
    @Schema(description = "操作人工号（关联HR系统）")
    private String operatorId;

    /**
     * 操作人姓名（冗余存储）
     */
    @Schema(description = "操作人姓名（冗余存储）")
    private String operatorName;

    /**
     * 申请人工号
     */
    @Schema(description = "申请人工号")
    private String applicantId;

    /**
     * 申请人姓名
     */
    @Schema(description = "申请人姓名")
    private String applicantName;

    /**
     * 申请日期（创建日期）
     */
    @Schema(description = "申请日期（创建日期）")
    private LocalDate applicationDate;

    /**
     * 审批状态
     */
    @Schema(description = "审批状态")
    private String status;

    /**
     * 审批备注/特殊情况说明
     */
    @Schema(description = "审批备注/特殊情况说明")
    private String remark;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @TableField(exist = false)
    @Schema(description = "明细")
    private List<TpmSparePartsReplaceDetailsEntity> detailsList;


    @TableField(exist = false)
    @Schema(description = "使用明细")
    private List<TpmSparePartsReplaceDetailsEntity> useList;
}