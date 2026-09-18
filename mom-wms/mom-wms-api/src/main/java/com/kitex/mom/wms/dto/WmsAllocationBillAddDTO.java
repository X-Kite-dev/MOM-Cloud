package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 调拨单保存实体
 *
 * @author kitex
 * @date 2024-12-18 14:09:03
 */
@Data
public class WmsAllocationBillAddDTO {

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

    @Schema(description = "永久幂等请求号；发起和接收分别使用新的请求号")
    private String requestId;

    @Schema(description = "Agent调拨路线策略ID")
    private Long transferRoutePolicyId;

    @Schema(description = "Agent调拨路线策略编码")
    private String transferRoutePolicyCode;

    @Schema(description = "Agent调拨路线策略快照JSON")
    private String transferRoutePolicySnapshot;

    @Schema(description = "运输方式")
    private String transportMode;

    @Schema(description = "紧急等级")
    private String urgencyLevel;

    @Schema(description = "预计到达时间")
    private LocalDateTime estimatedArrivalTime;

    @Schema(description = "来源调拨单ID；差异补调单使用")
    private Long parentAllocationBillId;

    @Schema(description = "关联短溢收差异单ID")
    private Long varianceCaseId;

    @Schema(description = "调拨目的：NORMAL/AGENT/VARIANCE_SUPPLEMENT")
    private String transferPurpose;

    @Schema(description = "结构化来源业务类型")
    private String sourceBusinessType;

    @Schema(description = "结构化来源业务单号")
    private String sourceBusinessNo;

    @Schema(description = "来源PLAN MRP记录ID")
    private Long sourceMrpId;

    @Schema(description = "来源缺料物料编码")
    private String sourceMaterialCode;

    @Schema(description = "来源缺料数量")
    private java.math.BigDecimal sourceShortageQuantity;

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
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;

    /**
     * 物料列表
     */
    @Schema(description = "物料列表")
    private List<WmsDetailDTO> details;
}
