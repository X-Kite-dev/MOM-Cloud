package com.kitex.mom.mes.vo;

import com.kitex.common.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 线边库实时库存VO
 *
 * @author kitex
 * @date 2025-04-27 11:59:15
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "线边库实时库存VO")
public class MesWarehouseLiveStockVO extends BaseEntity<MesWarehouseLiveStockVO> {

    /**
     * 工单号
     */
    @Schema(description = "工单号")
    private String taskCode;

    /**
     * 单据号
     */
    @Schema(description = "单据号")
    private String billNo;

    @Schema(description = "工序编码")
    private String routeCode;

    @Schema(description = "工序名称")
    private String routeName;

    @Schema(description = "员工工号")
    private String workerCode;

    @Schema(description = "员工姓名")
    private String workerName;

    @Schema(description = "派工班次编码")
    private String shiftCode;

    @Schema(description = "派工班次名称")
    private String shiftName;

    @Schema(description = "车间编码")
    private String workshopCode;

    @Schema(description = "车间名称")
    private String workshopName;

    @Schema(description = "包装箱码")
    private String wrapCode;

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

    @Schema(description = "审批状态")
    private String judgeStatus;

    /**
     * 物料明细类型 原材料、半成品、在制品
     */
    @Schema(description = "物料明细类型 原材料、半成品、在制品")
    private String matDetailType;
}