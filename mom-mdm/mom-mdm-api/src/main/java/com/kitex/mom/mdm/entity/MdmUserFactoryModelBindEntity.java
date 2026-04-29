package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户-工厂建模权限关联表
 *
 * @author claude
 * @date 2025-07-30
 */
@Data
@TenantTable
@TableName("mdm_user_factory_model_bind")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "用户-工厂建模权限关联")
public class MdmUserFactoryModelBindEntity extends BaseEntity<MdmUserFactoryModelBindEntity> {

    /**
     * 用户ID
     */
    @Schema(description = "用户ID")
    private Long userId;

    /**
     * 用户名(登录名)
     */
    @Schema(description = "用户名(登录名)")
    private String userName;

    /**
     * 用户姓名
     */
    @Schema(description = "用户姓名")
    private String name;

    /**
     * 工厂建模节点ID
     */
    @Schema(description = "工厂建模节点ID")
    private Long factoryModelId;

    /**
     * 工厂建模节点编码
     */
    @Schema(description = "工厂建模节点编码")
    private String factoryModelCode;

    /**
     * 工厂建模节点名称
     */
    @Schema(description = "工厂建模节点名称")
    private String factoryModelName;

    /**
     * 节点类型 (INDUSTRY/FACTORY/WORKSHOP/WAREHOUSE/LINE/STATION)
     */
    @Schema(description = "节点类型")
    private String nodeType;

    /**
     * 节点层级 (1-5)
     */
    @Schema(description = "节点层级")
    private Integer nodeLevel;

    /**
     * 权限类型 (READ/WRITE/ADMIN)
     */
    @Schema(description = "权限类型")
    private String permissionType;

    /**
     * 状态 (true-启用 false-禁用)
     */
    @Schema(description = "状态")
    private Boolean status;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}