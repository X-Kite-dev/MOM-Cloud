package com.kitex.mom.wms.entity;

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
 * 仓库盘点计划
 *
 * @author kitex
 * @date 2024-12-16 17:07:02
 */
@Data
@TenantTable
@TableName("wms_inside_inventory_plan")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "仓库盘点计划")
public class WmsInsideInventoryPlanEntity extends BaseEntity<WmsInsideInventoryPlanEntity> {

    /**
     * 盘点编号
     */
    @Schema(description = "盘点编号")
    private String planNo;

    /**
     * 仓库编号
     */
    @Schema(description = "仓库编号")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @Schema(description = "仓库名称")
    private String warehouseName;

    /**
     * 状态：0 新建/ 1正在执行中/2 已结束
     */
    @Schema(description = "状态：0正在执行中/1 已结束")
    private String status;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 盘点损益处理状态：0 待处理/ 1已处理
     */
    @Schema(description = "盘点损益处理状态：0 待处理/ 1已处理")
    private String handleState;

    @TableField(exist = false)
    private List<WmsInsideInventoryPlanDetailEntity> wmsInsideInventoryPlanDetailList;
}