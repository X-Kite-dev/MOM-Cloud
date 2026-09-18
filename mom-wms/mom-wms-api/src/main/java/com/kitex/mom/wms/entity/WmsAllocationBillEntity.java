package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 调拨单
 *
 * @author kitex
 * @date 2024-12-18 14:09:03
 */
@Data
@Accessors(chain = true)
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

    @Schema(description = "发起调拨永久幂等请求号")
    private String requestId;

    @Schema(description = "发起请求规范化快照，用于识别幂等号复用冲突")
    private String requestSnapshot;

    @Schema(description = "Agent调拨采用的路线策略ID")
    private Long transferRoutePolicyId;

    @Schema(description = "Agent调拨采用的路线策略编码")
    private String transferRoutePolicyCode;

    @Schema(description = "路线策略执行时快照JSON")
    private String transferRoutePolicySnapshot;

    @Schema(description = "运输方式")
    private String transportMode;

    @Schema(description = "紧急等级")
    private String urgencyLevel;

    @Schema(description = "执行时重新计算的预计到达时间")
    private LocalDateTime estimatedArrivalTime;

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
     * 调拨状态；历史0/1由累计升级脚本转换为命名状态
     */
    @Schema(description = "调拨状态：IN_TRANSIT/PARTIALLY_RECEIVED/RECEIVED/DISCREPANCY_PENDING/DISCREPANCY_RESOLVED/CANCELLED/PARTIALLY_CANCELLED")
    private String allocationState;

    @Schema(description = "源仓库存是否已在发起时扣减；历史单兼容标记")
    private Boolean sourceDeducted;

    @Schema(description = "调拨发起时间")
    private LocalDateTime issueTime;

    @Schema(description = "调拨发起人编码")
    private String issueBy;

    @Schema(description = "调拨发起人名称")
    private String issueByName;

    @Schema(description = "接收永久幂等请求号")
    private String receiveRequestId;

    @Schema(description = "接收请求规范化快照")
    private String receiveRequestSnapshot;

    @Schema(description = "最近一次分批接收时间")
    private LocalDateTime lastReceiptTime;

    @Schema(description = "最终短溢收差异单ID")
    private Long varianceCaseId;

    @Schema(description = "来源调拨单ID；差异补调单使用")
    private Long parentAllocationBillId;

    @Schema(description = "调拨目的：NORMAL/AGENT/VARIANCE_SUPPLEMENT")
    private String transferPurpose;

    @Schema(description = "触发调拨的结构化业务类型；Agent齐套调拨为PLAN_MRP")
    private String sourceBusinessType;

    @Schema(description = "触发调拨的业务单号；Agent齐套调拨保存生产计划号")
    private String sourceBusinessNo;

    @Schema(description = "触发Agent齐套调拨的PLAN MRP记录ID")
    private Long sourceMrpId;

    @Schema(description = "触发Agent齐套调拨的缺料物料编码")
    private String sourceMaterialCode;

    @Schema(description = "调拨执行时冻结的缺料数量")
    private java.math.BigDecimal sourceShortageQuantity;

    @Schema(description = "取消永久幂等请求号")
    private String cancelRequestId;

    @Schema(description = "取消请求规范化快照")
    private String cancelRequestSnapshot;

    @Schema(description = "取消原因")
    private String cancelReason;

    @Schema(description = "取消人编码")
    private String cancelBy;

    @Schema(description = "取消人名称")
    private String cancelByName;

    @Schema(description = "取消时间")
    private LocalDateTime cancelTime;

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
