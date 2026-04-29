package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseTreeEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 供应商分类表
 *
 * @author kitex
 * @date 2025-03-18 16:46:14
 */
@Data
@TenantTable
@TableName("mdm_supplier_class")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "供应商分类表")
public class MdmSupplierClassEntity extends BaseTreeEntity<MdmSupplierClassEntity> {

    /**
     * 供应商分类编码
     */
    @Schema(description = "供应商分类编码")
    private String code;

    /**
     * 供应商分类名称
     */
    @Schema(description = "供应商分类名称")
    private String name;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}