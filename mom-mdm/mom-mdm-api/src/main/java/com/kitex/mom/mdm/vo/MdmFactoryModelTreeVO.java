package com.kitex.mom.mdm.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工厂建模树形结构VO
 *
 * @author claude
 * @date 2025-07-30
 */
@Data
@Schema(description = "工厂建模树形结构VO")
public class MdmFactoryModelTreeVO {

    /**
     * 主键ID
     */
    @Schema(description = "主键ID")
    private Long id;

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
     * 节点类型名称
     */
    @Schema(description = "节点类型名称")
    private String typeName;

    /**
     * 排序
     */
    @Schema(description = "排序")
    private Integer sort;

    /**
     * 状态 (1=启用, 0=禁用)
     */
    @Schema(description = "状态 (1=启用, 0=禁用)")
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

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    /**
     * 子节点列表
     */
    @Schema(description = "子节点列表")
    private List<MdmFactoryModelTreeVO> children;

    /**
     * 是否有子节点
     */
    @Schema(description = "是否有子节点")
    private Boolean hasChildren;
}