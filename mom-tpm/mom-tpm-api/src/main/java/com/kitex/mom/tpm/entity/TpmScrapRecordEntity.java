package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 设备报废记录表
 *
 * @author kitex
 * @date 2025-02-21 11:43:16
 */
@Data
@TenantTable
@TableName("tpm_scrap_record")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "设备报废记录表")
public class TpmScrapRecordEntity extends BaseEntity<TpmScrapRecordEntity> {


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
     * 报废时间
     */
    @Schema(description = "报废时间")
    private LocalDate scrapTime;

    /**
     * 报废原因
     */
    @Schema(description = "报废原因")
    private String scrapReason;

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
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}