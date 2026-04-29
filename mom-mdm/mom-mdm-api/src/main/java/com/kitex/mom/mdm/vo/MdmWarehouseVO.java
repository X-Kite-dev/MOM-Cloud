package com.kitex.mom.mdm.vo;

import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 仓库
 *
 * @author kitex
 * @date 2024-10-28 13:48:36
 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@Schema(description = "仓库")
public class MdmWarehouseVO extends BaseEntity<MdmWarehouseVO> {

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
     * 状态 (true=启用, false=禁用)
     */
    @Schema(description = "状态 (true=启用, false=禁用)")
    private Boolean status;

    /**
     * 排序
     */
    @Schema(description = "排序")
    private Integer sortOrder;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码")
    private String factoryCode;

    /**
     * 工厂名称
     */
    @Schema(description = "工厂名称")
    private String factoryName;

}