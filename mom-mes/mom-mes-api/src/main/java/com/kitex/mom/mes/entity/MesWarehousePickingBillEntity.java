package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.yulichang.annotation.EntityMapping;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 生产领料单 实体类
 * </p>
 *
 * @author kitex
 * @date 2025-02-24 09:15:17
 */
@Data
@TenantTable
@TableName("mes_warehouse_picking_bill")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "生产领料单")
public class MesWarehousePickingBillEntity extends BaseEntity<MesWarehousePickingBillEntity> {

    /**
     * 领料单号
     */
    @Schema(description = "领料单号")
    private String billNo;

    /**
     * 工单编码
     */
    @Schema(description = "工单编码")
    private String taskCode;

    /**
     * 领料类型
     */
    @Schema(description = "领料类型")
    private String type;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String matCode;

    /**
     * 产品名称
     */
    @Schema(description = "产品名称")
    private String matName;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 领料套数
     */
    @Schema(description = "领料套数")
    private BigDecimal pickNum;

    /**
     * 车间
     */
    @Schema(description = "车间")
    private String workshop;

    /**
     * 领料人员
     */
    @Schema(description = "领料人员")
    private String picker;

    /**
     * 领料时间
     */
    @Schema(description = "领料时间")
    private LocalDateTime pickTime;

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
     * 状态 0新建、1待接收、2已完成
     */
    @Schema(description = "状态 0新建、1待接收、2已完成")
    private String status;

    /**
     * 领料单明细
     */
    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "billId")
    private List<MesWarehousePickingBillDetailEntity> detailList;

    /**
     * 领料单接收明细
     */
    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "billId")
    private List<MesWarehouseMaterialInBillEntity> receiveDetailList;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}
