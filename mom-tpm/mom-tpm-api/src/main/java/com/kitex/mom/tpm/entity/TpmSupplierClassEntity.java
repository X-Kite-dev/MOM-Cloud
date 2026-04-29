package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 设备供应商类型表
 *
 * @author kitex
 * @date 2025-03-25 13:42:38
 */
@Data
@TableName("tpm_supplier_class")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "设备供应商类型表")
public class TpmSupplierClassEntity extends BaseEntity<TpmSupplierClassEntity> {


    /**
     * 主键ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    /**
     * 类型编码
     */
    @Schema(description = "类型编码")
    private String code;

    /**
     * 类型名称
     */
    @Schema(description = "类型名称")
    private String name;

    /**
     * 备注说明
     */
    @Schema(description = "备注说明")
    private String remark;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}