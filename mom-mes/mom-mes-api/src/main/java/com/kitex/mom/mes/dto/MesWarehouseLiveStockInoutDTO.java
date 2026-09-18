package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 线边库实时库存出入库参数
 *
 * @author kitex
 * @date 2025-02-20 13:54:13
 */
@Data
@Schema(description = "线边库实时库存出入库参数")
public class MesWarehouseLiveStockInoutDTO {
    /** 受控线边签收操作ID，只能由业务服务赋值。 */
    private Long materialReceiptId;

    /**
     * 业务类型
     */
    @NotBlank(message = "业务类型为空")
    @Schema(description = "业务类型")
    private String bizType;

    /**
     * 单据号
     */
    @Schema(description = "单据号")
    private String billNo;

    @Schema(description = "工序任务编号")
    private String produceTaskCode;

    @Schema(description = "出库时是否允许使用未绑定生产任务的公共线边库存")
    private Boolean allowUnassignedProduceTask;

    @Schema(description = "工序编码")
    private String routeCode;

    @Schema(description = "工序名称")
    private String routeName;

    /**
     * 单据id
     */
    @Schema(description = "单据id")
    private Long billId;

    @Schema(description = "来源业务唯一号")
    private String resourceNo;

    @Schema(description = "库存来源报工记录ID")
    private Long sourceReportId;

    /**
     * 物料编码
     */
    @NotBlank(message = "物料编码为空")
    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "WMS物料预留单号")
    private String reservationNo;

    @Schema(description = "BOM需求物料编码")
    private String requirementMatCode;

    @Schema(description = "BOM需求物料名称")
    private String requirementMatName;

    @Schema(description = "是否使用替代料")
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

    @Schema(description = "内部冲销是否允许扣减待判或拒绝库存")
    private Boolean allowQualityBlocked;

    /**
     * 物料明细类型 原材料、半成品、在制品
     */
    @Schema(description = "物料明细类型 原材料、半成品、在制品")
    private String matDetailType;

    /**
     * 创建人名称
     */
    @Schema(description = "创建人名称")
    private String createByName;

    /**
     * 更新人名称
     */
    @Schema(description = "更新人名称")
    private String updateByName;

    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;
}
