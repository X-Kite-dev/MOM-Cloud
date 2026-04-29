package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工厂建模（五级树形结构）
 * 级别：产业 -> 工厂 -> 车间/仓库 -> 线体 -> 工位
 *
 * @author claude
 * @date 2025-07-30
 */
@Data
@TenantTable
@TableName("mdm_factory_model")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工厂建模")
public class MdmFactoryModelEntity extends BaseEntity<MdmFactoryModelEntity> {

    /**
     * 节点编码
     */
    @Schema(description = "节点编码")
    private String code;

    /**
     * 节点名称
     */
    @Schema(description = "节点名称")
    private String name;

    /**
     * 父节点ID
     */
    @Schema(description = "父节点ID")
    private Long parentId;

    /**
     * 节点级别 (1=产业, 2=工厂, 3=车间/仓库, 4=线体, 5=工位)
     */
    @Schema(description = "节点级别 (1=产业, 2=工厂, 3=车间/仓库, 4=线体, 5=工位)")
    private Integer level;

    /**
     * 节点类型 (INDUSTRY=产业, FACTORY=工厂, WORKSHOP=车间, WAREHOUSE=仓库, LINE=线体, STATION=工位)
     */
    @Schema(description = "节点类型")
    private String type;

    /**
     * 排序
     */
    @Schema(description = "排序")
    private Integer sort;

    /**
     * 状态 (true=启用, false=禁用)
     */
    @Schema(description = "状态 (true=启用, false=禁用)")
    private Boolean status;

    /**
     * 负责人
     */
    @Schema(description = "负责人")
    private String manager;

    /**
     * 联系电话
     */
    @Schema(description = "联系电话")
    private String phone;

    /**
     * 地址
     */
    @Schema(description = "地址")
    private String address;

    /**
     * 描述
     */
    @Schema(description = "描述")
    private String description;

    /**
     * 扩展属性（JSON格式）
     */
    @Schema(description = "扩展属性（JSON格式）")
    private String properties;


}