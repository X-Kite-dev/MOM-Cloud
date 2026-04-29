package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 调拨单
 *
 * @author kitex
 * @date 2024-12-18 14:09:03
 */
@Data
@TenantTable
@TableName("wms_allocation_bill")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "调拨单")
public class WmsAllocationBillEntity extends BaseEntity<WmsAllocationBillEntity> {

    /**
     * 调拨单号
     */
    @Schema(description = "调拨单号")
    private String billNo;

    /**
     * 旧仓库编码
     */
    @Schema(description = "旧仓库编码")
    private String oldWarehouseCode;

    /**
     * 旧仓库名称
     */
    @Schema(description = "旧仓库名称")
    private String oldWarehouseName;

    /**
     * 新仓库编码
     */
    @Schema(description = "新仓库编码")
    private String newWarehouseCode;

    /**
     * 新仓库名称
     */
    @Schema(description = "新仓库名称")
    private String newWarehouseName;

    /**
     * 调拨完成状态, 0未完成 1完成
     */
    @Schema(description = "调拨完成状态, 0未完成 1完成")
    private String allocationState;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 完成时间
     */
    @Schema(description = "完成时间")
    private LocalDateTime finishTime;

    /**
     * 完成操作人
     */
    @Schema(description = "完成操作人")
    private String finishByName;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}