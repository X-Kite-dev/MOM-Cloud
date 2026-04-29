package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 设备报废申请表
 *
 * @author kitex
 * @date 2025-02-21 11:34:10
 */
@Data
@TenantTable
@TableName("tpm_scrap_apply")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "设备报废申请表")
public class TpmScrapApplyEntity extends BaseEntity<TpmScrapApplyEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

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
     * 申请时间
     */
    @Schema(description = "申请时间")
    private LocalDate applyTime;

    /**
     * 申请原因
     */
    @Schema(description = "申请原因")
    private String applyReason;

    /**
     * 报废时间
     */
    @Schema(description = "报废时间")
    private LocalDate scrapTime;

    /**
     * 工装分类编码
     */
    @Schema(description = "工装分类编码")
    private String classCode;

    /**
     * 工装分类名称
     */
    @Schema(description = "工装分类名称")
    private String className;

    /**
     * 审核人编码
     */
    @Schema(description = "审核人编码")
    private String reviewerCode;

    /**
     * 审核人名称
     */
    @Schema(description = "审核人名称")
    private String reviewerName;

    /**
     * 审核时间
     */
    @Schema(description = "审核时间")
    private LocalDateTime reviewerTime;

    /**
     * 申请状态 1 审批通过 2 已作废
     */
    @Schema(description = "申请状态 1 审批通过 2 已作废")
    private String state;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}