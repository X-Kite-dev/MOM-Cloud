package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <p>
 * 线边库实时库存 实体类
 * </p>
 *
 * @author kitex
 * @date 2025-02-20 13:54:13
 */
@Data
@TenantTable
@TableName("mes_warehouse_live_stock")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "线边库实时库存")
public class MesWarehouseLiveStockEntity extends BaseEntity<MesWarehouseLiveStockEntity> {
    /** 独立实物质量案件；原报工放行不能解除此隔离。 */
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long qualityCaseId;
    /** 隔离根库存最后一次数量变动回执；新放行行以origin字段保留谱系。 */
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long qualityDispositionReceiptId;
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long qualityOriginCaseId;
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    private Long qualityReleaseReceiptId;
    private Long materialReceiptId;

    @Schema(description = "库存来源报工记录ID")
    private Long sourceReportId;

    @Schema(description = "最近一次转运确认的现场库位，独立于来源工序库位")
    private String physicalLocationCode;
    private String physicalLocationName;
    private LocalDateTime physicalConfirmedTime;

    /**
     * 单据号
     */
    @Schema(description = "单据号")
    private String billNo;

    /**
     * 工序任务编号
     */
    @Schema(description = "工序任务编号")
    private String produceTaskCode;

    @Schema(description = "来源工序编码")
    private String routeCode;

    @Schema(description = "来源工序名称")
    private String routeName;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "WMS物料预留单号")
    private String reservationNo;

    @Schema(description = "BOM需求物料编码")
    private String requirementMatCode;

    @Schema(description = "BOM需求物料名称")
    private String requirementMatName;

    @Schema(description = "是否为替代料")
    private Boolean substituteFlag;

    @Schema(description = "替代比例")
    private BigDecimal substituteRatio;

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
     * 批次号
     */
    @Schema(description = "批次号")
    private String matBatch;

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
     * 有效截止日期
     */
    @Schema(description = "有效截止日期")
    private LocalDate matValidEndDate;

    /**
     * 物料条码
     */
    @Schema(description = "物料条码")
    private String matBarcode;

    /**
     * 物料条码图片
     */
    @Schema(description = "物料条码图片")
    private String matBarcodeImage;

    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @Schema(description = "供应商名称")
    private String supplierName;

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
     * 容器编码
     */
    @Schema(description = "容器编码")
    private String containerCode;

    /**
     * 容器名称
     */
    @Schema(description = "容器名称")
    private String containerName;

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
     * 入库时间
     */
    @Schema(description = "入库时间")
    private LocalDateTime inTime;

    /**
     * 数量
     */
    @Schema(description = "数量")
    private BigDecimal num;

    /**
     * 状态
     */
    @Schema(description = "状态")
    private String status;

    /**
     * 质检状态
     */
    @Schema(description = "质检状态")
    private String inspectionStatus;

    /**
     * 审批状态
     */
    @Schema(description = "审批状态")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String judgeStatus;

    /**
     * 物料明细类型 原材料、半成品、在制品
     */
    @Schema(description = "物料明细类型 原材料、半成品、在制品")
    private String matDetailType;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
