package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 仓库盘点计划
 *
 * @author kitex
 * @date 2024-12-16 17:07:02
 */
@Data
@TenantTable
@TableName("wms_inside_inventory_plan_detail")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "wms_inside_inventory_plan_detail")
public class WmsInsideInventoryPlanDetailEntity extends BaseEntity<WmsInsideInventoryPlanDetailEntity> {

    /**
     * 盘点计划ID
     */
    @Schema(description = "盘点计划ID")
    private Long planId;
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
     * 物料编号
     */
    @Schema(description = "物料编号")
    private String matCode;
    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;
    /**
     * 物料单位
     */
    @Schema(description = "物料单位")
    private String matUnit;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 物料分组
     */
    @Schema(description = "物料分组")
    private String matGroup;

    /**
     * 物料分组名称
     */
    @Schema(description = "物料分组名称")
    private String matGroupName;

    /**
     * 库位编码
     */
    @Schema(description = "库位编码")
    private String siteCode;
    /**
     * 库存名称
     */
    @Schema(description = "库存名称")
    private String siteName;
    /**
     * 库存数量
     */
    @Schema(description = "库存数量")
    private Double currentNumber;
    /**
     * 盘点数量
     */
    @Schema(description = "盘点数量")
    private Double inventoryNum;
    /**
     * 物料批次
     */
    @Schema(description = "物料批次")
    private String matBatch;
    /**
     * 盘点录入状态：0 新建/ 1已完成
     */
    @Schema(description = "盘点录入状态：0 新建/ 1已完成")
    private String status;
    /**
     * 损益处理状态：0 未完成/ 1已完成
     */
    @Schema(description = "损益处理状态：0 未完成/ 1已完成")
    private String plStatus;
    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}