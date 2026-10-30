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
     * 状态：0 待录入/1 已录入/2 已作废
     */
    @Schema(description = "状态：0 待录入/1 已录入/2 已作废")
    private String status;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 盘点损益处理状态：0 待处理/1 已处理/2 已作废
     */
    @Schema(description = "盘点损益处理状态：0 待处理/1 已处理/2 已作废")
    private String handleState;

    @Schema(description = "库存写入模式：SNAPSHOT动态盘点；FREEZE_COUNT实盘录入期间冻结选定范围")
    private String stockFreezeMode;

    @Schema(description = "作废原因")
    private String cancelReason;

    @Schema(description = "作废请求号")
    private String cancelRequestId;

    @Schema(description = "作废操作人")
    private String cancelBy;

    @Schema(description = "作废时间")
    private LocalDateTime cancelTime;

    @TableField(exist = false)
    private List<WmsInsideInventoryPlanDetailEntity> wmsInsideInventoryPlanDetailList;
}
