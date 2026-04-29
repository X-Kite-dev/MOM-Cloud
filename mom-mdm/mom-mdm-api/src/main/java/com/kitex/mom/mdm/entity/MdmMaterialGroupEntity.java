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
 * 物料分组表
 *
 * @author kitex
 * @date 2024-09-18 10:00:05
 */
@Data
@TenantTable
@TableName("mdm_material_group")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "物料分组表")
public class MdmMaterialGroupEntity extends BaseTreeEntity<MdmMaterialGroupEntity> {

    /**
     * 分组名称
     */
    @Schema(description = "分组名称")
    private String groupName;

    /**
     * 分组编码
     */
    @Schema(description = "分组编码")
    private String groupCode;

    /**
     * 排序
     */
    @Schema(description = "排序")
    private Integer sortOrder;

    /**
     * 描述
     */
    @Schema(description = "描述")
    private String description;

    /**
     * 启用
     */
    @Schema(description = "启用")
    private Boolean enabled;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}