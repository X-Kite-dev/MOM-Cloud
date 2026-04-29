package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 库位
 *
 * @author kitex
 * @date 2024-12-13 18:20:13
 */
@Data
@TenantTable
@TableName("mdm_warehouse_location")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "库位")
public class MdmWarehouseLocationEntity extends BaseEntity<MdmWarehouseLocationEntity> {

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
     * 库位编码
     */
    @Schema(description = "库位编码")
    private String locationCode;

    /**
     * 库位名称
     */
    @Schema(description = "库位名称")
    private String locationName;

    /**
     * 父id root节点设置为-1
     */
    @Schema(description = "父id")
    private Long parentId;

    /**
     * 层级 1库区2货架3库位
     */
    @Schema(description = "层级")
    private Integer locationLevel;

    /**
     * 货架层数
     */
    @Schema(description = "货架层数")
    private Integer shelvesNumber;

    /**
     * 状态 (true=启用, false=禁用)
     */
    @Schema(description = "状态 (true=启用, false=禁用)")
    private Boolean status;

    /**
     * 排序
     */
    @Schema(description = "排序")
    private Integer sort;

    /**
     * 说明
     */
    @Schema(description = "说明")
    private String description;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 是否有子节点
     */
    @TableField(exist = false)
    @Schema(description = "是否有子节点")
    private Boolean hasChildren;

    /**
     * 是否跳过工厂过滤
     */
    @TableField(exist = false)
    @Schema(description = "是否跳过工厂过滤")
    private Boolean skipFactory;

    /**
     * 子节点
     */
    @TableField(exist = false)
    @Schema(description = "子节点")
    private List<MdmWarehouseLocationEntity> children;

}