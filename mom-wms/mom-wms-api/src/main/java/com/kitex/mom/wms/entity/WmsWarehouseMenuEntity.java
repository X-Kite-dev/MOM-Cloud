package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 仓库菜单配置
 *
 * @author kitex
 * @date 2025-01-13 14:41:50
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("wms_warehouse_menu")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "仓库菜单配置")
public class WmsWarehouseMenuEntity extends BaseEntity<WmsWarehouseMenuEntity> {

    /**
     * 菜单名称
     */
    @Schema(description = "菜单名称")
    private String name;

    /**
     * 权限标识
     */
    @Schema(description = "权限标识")
    private String permission;

    /**
     * 路由路径
     */
    @Schema(description = "路由路径")
    private String path;

    /**
     * 组件
     */
    @Schema(description = "组件")
    private String component;

    /**
     * 父菜单ID
     */
    @Schema(description = "父菜单ID")
    private Long parentId;

    /**
     * 菜单图标
     */
    @Schema(description = "菜单图标")
    private String icon;

    /**
     * 排序值，越小越靠前
     */
    @Schema(description = "排序值，越小越靠前")
    private Integer sortOrder;

    /**
     * 菜单类型，0目录，1菜单，2按钮
     */
    @Schema(description = "菜单类型，0目录，1菜单，2按钮")
    private String menuType;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}