package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 物料详情传输实体
 *
 * @author kitex
 * @date 2024-12-19 10:05:21
 */
@Data
public class WmsDetailDTO {

    /**
     * 单据id
     */
    @Schema(description = "单据id")
    private Long billId;

    @Schema(description = "调拨发起出库流水ID，接收时必须回传")
    private Long flowId;

    @Schema(description = "调拨源实时库存ID")
    private Long sourceStockId;

    @Schema(description = "调拨发出时的源仓容器编码；与接收容器分别保存")
    private String oldContainerCode;

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
     * 物料类型
     */
    @Schema(description = "物料类型")
    private String matGroup;

    /**
     * 物料类型名称
     */
    @Schema(description = "物料类型名称")
    private String matGroupName;

    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 物料批次
     */
    @Schema(description = "物料批次")
    private String matBatch;

    /**
     * 包装规格
     */
    @Schema(description = "包装规格")
    private String matPackSpec;

    /**
     * 包装单位
     */
    @Schema(description = "包装单位")
    private String operateUnit;

    /**
     * 操作数量（包装数量）
     */
    @Schema(description = "操作数量（包装数量）")
    private Double operateNum;

    @Schema(description = "发起调拨数量")
    private BigDecimal issuedQuantity;

    @Schema(description = "已累计接收数量，不含隔离中的溢收")
    private BigDecimal receivedQuantity;

    @Schema(description = "未结数量；待实物退回阶段表示尚未返还源仓")
    private BigDecimal inTransitQuantity;

    @Schema(description = "最终短收数量")
    private BigDecimal shortQuantity;

    @Schema(description = "审核通过的退回保管异常数量，不是已入库数量")
    private BigDecimal returnExceptionQuantity;

    @Schema(description = "隔离或已处置的溢收数量")
    private BigDecimal overQuantity;

    @Schema(description = "终态调拨中未被接收、取消返还或短收差异闭合的数量；大于0须人工核账")
    private BigDecimal unreconciledQuantity;

    @Schema(description = "行状态：IN_TRANSIT/PARTIAL/RETURN_PENDING/RETURN_EXCEPTION_PENDING/RETURN_EXCEPTION_CLOSED/RECEIVED/VARIANCE_PENDING/VARIANCE_RESOLVED/CANCELLED/RECONCILIATION_REQUIRED")
    private String transferLineState;

    /**
     * 供应商
     */
    @Schema(description = "供应商")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @Schema(description = "供应商名称")
    private String supplierName;

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
     * 新库位编码
     */
    @Schema(description = "新库位编码")
    private String newSiteCode;

    /**
     * 新库存名称
     */
    @Schema(description = "新库存名称")
    private String newSiteName;

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
     * 旧库位编码
     */
    @Schema(description = "旧库位编码")
    private String oldSiteCode;

    /**
     * 旧库存名称
     */
    @Schema(description = "旧库存名称")
    private String oldSiteName;

    /**
     * 容器编码
     */
    @Schema(description = "容器编码")
    private String containerCode;

    /**
     * 入库时间
     */
    @Schema(description = "入库时间")
    private LocalDateTime inTime;

    /**
     * 数量
     */
    @Schema(description = "数量")
    private Double matNum;

    /**
     * 单位
     */
    @Schema(description = "单位")
    private String matUnit;

    /**
     * 生产日期
     */
    @Schema(description = "生产日期")
    private LocalDate matProduceDate;

    /**
     * 有效期
     */
    @Schema(description = "有效期")
    private Integer matValidPeriod;

    /**
     * 有效截至日期
     */
    @Schema(description = "有效截至日期")
    private LocalDate matValidEndDate;

    /**
     * 质检状态;creating_待检,ongoing_进行中，pass_合格，no_pass_不合格
     */
    @Schema(description = "质检状态;creating_待检,ongoing_进行中，pass_合格，no_pass_不合格")
    private String qualityStatus;

    /**
     * 质检结果
     */
    @Schema(description = "质检结果")
    private String qualityResult;

    /**
     * 状态 是否被锁定;0否 1是
     */
    @Schema(description = "状态 是否被锁定;0否 1是")
    private Integer status;

    /**
     * 扩展字段
     */
    @Schema(description = "扩展字段")
    private String expansion;

    /**
     * 操作类型;in入库、out出库
     */
    @Schema(description = "操作类型;in入库、out出库")
    private String operateType;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;
}
