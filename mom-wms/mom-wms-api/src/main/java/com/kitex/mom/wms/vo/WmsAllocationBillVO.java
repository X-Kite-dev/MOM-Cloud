package com.kitex.mom.wms.vo;

import com.kitex.mom.wms.dto.WmsDetailDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 调拨单返回实体
 *
 * @author kitex
 * @date 2024-12-18 14:09:03
 */
@Data
public class WmsAllocationBillVO {

    /**
     * 调拨单id
     */
    @Schema(description = "调拨单id")
    private Long id;

    /**
     * 调拨单号
     */
    @Schema(description = "调拨单号")
    private String billNo;

    @Schema(description = "调拨状态，包含部分接收与短溢差异阶段")
    private String allocationState;

    @Schema(description = "源仓库存是否已在发起时扣减")
    private Boolean sourceDeducted;

    @Schema(description = "调拨路线策略ID")
    private Long transferRoutePolicyId;

    @Schema(description = "调拨路线策略编码")
    private String transferRoutePolicyCode;

    @Schema(description = "调拨路线策略执行快照")
    private String transferRoutePolicySnapshot;

    @Schema(description = "运输方式")
    private String transportMode;

    @Schema(description = "紧急等级")
    private String urgencyLevel;

    @Schema(description = "预计到达时间")
    private LocalDateTime estimatedArrivalTime;

    @Schema(description = "最近一次分批接收时间")
    private LocalDateTime lastReceiptTime;

    @Schema(description = "短溢收差异单ID")
    private Long varianceCaseId;

    @Schema(description = "来源调拨单ID")
    private Long parentAllocationBillId;

    @Schema(description = "调拨目的")
    private String transferPurpose;

    @Schema(description = "取消原因")
    private String cancelReason;

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
     * 旧仓库类型
     */
    @Schema(description = "旧仓库类型")
    private String oldWarehouseType;

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
     * 新仓库类型
     */
    @Schema(description = "新仓库类型")
    private String newWarehouseType;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 类型: product 成品 purchase 原辅 outsource 外协 research 研发
     */
    @Schema(description = "类型: product 成品 purchase 原辅 outsource 外协 research 研发")
    private String billType;

    /**
     * 组织id
     */
    @Schema(description = "组织id")
    private String factoryCode;

    /**
     * 物料列表
     */
    @Schema(description = "物料列表")
    private List<WmsDetailDTO> details;
}
