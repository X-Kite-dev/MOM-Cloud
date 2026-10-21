package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
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
 * 生产领料 实体类
 * </p>
 *
 * @author kitex
 * @date 2025-02-24 09:15:17
 */
@Data
@TenantTable
@TableName("mes_warehouse_material_in_bill")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "生产领料")
public class MesWarehouseMaterialInBillEntity extends BaseEntity<MesWarehouseMaterialInBillEntity> {

    /**
     * 单据id
     */
    @Schema(description = "单据id")
    private Long billId;

    /**
     * 单据号
     */
    @Schema(description = "单据号")
    private String billNo;

    /**
     * 来源单号
     */
    @Schema(description = "来源单号")
    private String resourceNo;

    @Schema(description = "WMS出库明细id，用于发料回调和补偿重放幂等")
    private Long wmsOutDetailId;

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

    @Schema(description = "BOM需求数量当量")
    private BigDecimal requirementMatNum;

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
     * 通知入库数量
     */
    @Schema(description = "通知入库数量")
    private BigDecimal noticeInNum;

    /**
     * 实际入库数量
     */
    @Schema(description = "实际入库数量")
    private BigDecimal inNum;

    /**
     * 首次进入受控签收时冻结的历史净收量，不伪造历史签收操作。
     */
    private BigDecimal receiptBaselineQuantity;

    /**
     * 入库时间
     */
    @Schema(description = "入库时间")
    private LocalDateTime inTime;

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
     * 容器码
     */
    @Schema(description = "容器码")
    private String containerCode;

    /**
     * 容器名称
     */
    @Schema(description = "容器名称")
    private String containerName;

    /**
     * 经办人
     */
    @Schema(description = "经办人")
    private String handleBy;

    /**
     * 经办人姓名
     */
    @Schema(description = "经办人姓名")
    private String handleByName;

    /**
     * 发送部门
     */
    @Schema(description = "发送部门")
    private String sendingDeptCode;

    /**
     * 发送部门名称
     */
    @Schema(description = "发送部门名称")
    private String sendingDeptName;

    /**
     * 接收部门
     */
    @Schema(description = "接收部门")
    private String receiptDeptCode;

    /**
     * 接收部门名称
     */
    @Schema(description = "接收部门名称")
    private String receiptDeptName;

    /**
     * 状态 待入库 已入库
     */
    @Schema(description = "状态 待入库 已入库")
    private String status;

    /**
     * 质检状态
     */
    @Schema(description = "质检状态")
    private String inspectionStatus;

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
