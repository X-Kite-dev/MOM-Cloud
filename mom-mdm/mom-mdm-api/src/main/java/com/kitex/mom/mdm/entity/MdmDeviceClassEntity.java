package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 工装分类表（设备）
 *
 * @author kitex
 * @date 2025-02-05 10:28:48
 */
@Data
@TenantTable
@TableName("mdm_device_class")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工装分类表（设备）")
public class MdmDeviceClassEntity extends BaseEntity<MdmDeviceClassEntity> {


    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "id")
    private Long id;

    /**
     * 编码
     */
    @Schema(description = "编码")
    private String code;

    /**
     * 名称
     */
    @Schema(description = "名称")
    private String name;

    /**
     * 父编码
     */
    @Schema(description = "父编码")
    private String parentCode;


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
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;


    /**
     * 子级
     */
    @TableField(exist = false)
    @Schema(description = "子级")
    private List<MdmDeviceClassEntity> children;
}