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
 * 生产领料单明细 实体类
 * </p>
 *
 * @author kitex
 * @date 2025-02-24 09:15:17
 */
@Data
@TenantTable
@TableName("mes_warehouse_picking_bill_detail")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "生产领料单明细")
public class MesWarehousePickingBillDetailEntity extends BaseEntity<MesWarehousePickingBillDetailEntity> {

    /**
     * 领料单ID
     */
    @Schema(description = "领料单id")
    private Long billId;

    /**
     * 领料单号
     */
    @Schema(description = "领料单号")
    private String billNo;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
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
     * 单位
     */
    @Schema(description = "单位")
    private String matUnit;

    /**
     * 数量
     */
    @Schema(description = "数量")
    private BigDecimal matNum;

    /**
     * 发货仓库
     */
    @Schema(description = "发货仓库")
    private String warehouseCode;

    /**
     * 发货仓库名称
     */
    @Schema(description = "发货仓库名称")
    private String warehouseName;

    /**
     * 一套的数量
     */
    @Schema(description = "一套的数量")
    private BigDecimal quantity;

    /**
     * 状态 是否已拣货（是否可接收）全部明细拣货完成后主表状态改为待接收
     */
    @Schema(description = "状态")
    private String status;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
