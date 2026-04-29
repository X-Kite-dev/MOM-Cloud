package com.kitex.mom.pms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 供应商-供货附件表
 *
 * @author kitex
 * @date 2025-03-19 18:09:02
 */
@Data
@TenantTable
@TableName("pms_supplier_file")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "供应商-供货附件表")
public class PmsSupplierFileEntity extends BaseEntity<PmsSupplierFileEntity> {

    /**
     * 供应商主键id
     */
    @Schema(description = "供应商主键id")
    private Long supplierId;

    /**
     * 原始文件名
     */
    @Schema(description = "附件名称")
    private String attachmentName;

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

    /**
     * 文件生效期
     */
    @Schema(description = "文件生效期")
    private LocalDate effectiveDate;

    /**
     * 文件失效期
     */
    @Schema(description = "文件失效期")
    private LocalDate expiryDate;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;
}