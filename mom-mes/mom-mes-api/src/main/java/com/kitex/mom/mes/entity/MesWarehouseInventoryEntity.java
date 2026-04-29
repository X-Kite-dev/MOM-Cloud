package com.kitex.mom.mes.entity;

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
 * <p>
 * 仓库盘点计划 实体类
 * </p>
 *
 * @author kitex
 * @date 2024-12-16 17:07:02
 */
@Data
@TenantTable
@TableName("mes_warehouse_inventory")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "仓库盘点计划")
public class MesWarehouseInventoryEntity extends BaseEntity<MesWarehouseInventoryEntity> {

    /**
     * 盘点编号
     */
    @Schema(description = "盘点编号")
    private String code;

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
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 物料条码
     */
    @Schema(description = "物料条码")
    private String matBarcode;

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
     * 库位编号
     */
    @Schema(description = "库位编号")
    private String siteCode;

    /**
     * 库位名称
     */
    @Schema(description = "库位名称")
    private String siteName;

    /**
     * 状态：0 执行中 / 1 已完成
     */
    @Schema(description = "状态：0 执行中 / 1 已完成")
    private String status;

    /**
     * 盘点损益处理状态：0 待处理/ 1已处理
     */
    @Schema(description = "盘点损益处理状态：0 待处理/ 1已处理")
    private String handleState;

    /**
     * 盘点明细
     */
    @TableField(exist = false)
    private List<MesWarehouseInventoryRecordEntity> detailList;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
