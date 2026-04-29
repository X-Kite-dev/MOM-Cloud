package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * <p>
 * 仓库盘点记录 实体类
 * </p>
 *
 * @author kitex
 * @date 2025-2-25 11:40:21
 */
@Data
@TenantTable
@TableName("mes_warehouse_inventory_record")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "仓库盘点记录")
public class MesWarehouseInventoryRecordEntity extends BaseEntity<MesWarehouseInventoryRecordEntity> {

    /**
     * 盘点编码
     */
    @Schema(description = "盘点编码")
    private String inventoryCode;
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
     * 物料批次
     */
    @Schema(description = "物料批次")
    private String matBatch;

    /**
     * 单位
     */
    @Schema(description = "单位")
    private String matUnit;

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
    private String siteCode;
    /**
     * 库位名称
     */
    @Schema(description = "库位名称")
    private String siteName;
    /**
     * 库存数量
     */
    @Schema(description = "库存数量")
    private BigDecimal currentNum;
    /**
     * 盘点数量
     */
    @Schema(description = "盘点数量")
    private BigDecimal inventoryNum;
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
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
