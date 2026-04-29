package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 供应商资质表
 *
 * @author kitex
 * @date 2025-03-19 16:35:48
 */
@Data
@TenantTable
@TableName("pms_supplier_aptitude")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "供应商资质表")
public class PmsSupplierAptitudeEntity extends BaseEntity<PmsSupplierAptitudeEntity> {

    /**
     * 供应商表主键id
     */
    @Schema(description = "供应商表主键id")
    private Long supplierId;

    /**
     * 资质名称
     */
    @Schema(description = "资质名称")
    private String aptitudeName;

    /**
     * 资质有效期起
     */
    @Schema(description = "资质有效期起")
    private LocalDate aptitudeValidFrom;

    /**
     * 资质有效期止
     */
    @Schema(description = "资质有效期止")
    private LocalDate aptitudeValidTo;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 原始文件名
     */
    @Schema(description = "原始文件名")
    private String name;

    /**
     * 文件名
     */
    @Schema(description = "文件名")
    private String fileName;

    /**
     * URL
     */
    @Schema(description = "URL")
    private String url;
}