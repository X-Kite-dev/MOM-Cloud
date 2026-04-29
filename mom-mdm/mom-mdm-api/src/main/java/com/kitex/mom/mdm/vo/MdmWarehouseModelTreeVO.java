package com.kitex.mom.mdm.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 仓库建模树形结构VO
 *
 * @author kitex
 * @date 2025-09-22 10:06:31
 */
@Data
@Schema(description = "仓库建模树形结构VO")
public class MdmWarehouseModelTreeVO {

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
     * 层级 INDUSTRY产业FACTORY工厂0仓库1库区2货架3库位
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
     * 状态 (true=启用, false=禁用)
     */
    @Schema(description = "状态 (true=启用, false=禁用)")
    private Boolean status;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    private String factoryCode;

    /**
     * 仓库编码
     */
    @Schema(description = "仓库编码")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @Schema(description = "仓库名称")
    private String warehouseName;

    /**
     * 子节点列表
     */
    @Schema(description = "子节点列表")
    private List<MdmWarehouseModelTreeVO> children;

    /**
     * 是否有子节点
     */
    @Schema(description = "是否有子节点")
    private Boolean hasChildren;
}