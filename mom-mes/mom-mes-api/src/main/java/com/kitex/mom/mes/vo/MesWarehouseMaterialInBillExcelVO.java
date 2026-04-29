package com.kitex.mom.mes.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 生产领料
 *
 * @author kitex
 * @date 2025-02-24 09:15:17
 */
@Data
@ColumnWidth(18)
@Schema(description = "生产领料")
public class MesWarehouseMaterialInBillExcelVO {

    /**
     * 序号
     */
    @ColumnWidth(8)
    @ExcelProperty("序号")
    @Schema(description = "序号")
    private Integer rowNo;

    /**
     * 单据号
     */
    @ExcelProperty("单据号")
    @Schema(description = "单据号")
    private String billNo;

    /**
     * 来源单号
     */
    @ExcelProperty("来源单号")
    @Schema(description = "来源单号")
    private String resourceNo;

    /**
     * 物料编码
     */
    @ExcelProperty("物料编码")
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @ExcelProperty("物料名称")
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 规格型号
     */
    @ExcelProperty("规格型号")
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 批次号
     */
    @ExcelProperty("批次号")
    @Schema(description = "批次号")
    private String matBatch;

    /**
     * 状态 0待入库 1已入库
     */
    @ExcelProperty("状态")
    @Schema(description = "状态 0待入库 1已入库")
    private String status;

    /**
     * 通知入库数量
     */
    @ExcelProperty("通知入库数量")
    @Schema(description = "通知入库数量")
    private BigDecimal noticeInNum;

    /**
     * 实际入库数量
     */
    @ExcelProperty("实际入库数量")
    @Schema(description = "实际入库数量")
    private BigDecimal inNum;

    /**
     * 单位
     */
    @ExcelProperty("单位")
    @Schema(description = "单位")
    private String matUnit;

    /**
     * 生产日期
     */
    @ExcelProperty("生产日期")
    @Schema(description = "生产日期")
    private LocalDate matProduceDate;

    /**
     * 有效期
     */
    @ExcelProperty("有效期")
    @Schema(description = "有效期")
    private Integer matValidPeriod;

    /**
     * 有效截止日期
     */
    @ExcelProperty("有效截止日期")
    @Schema(description = "有效截止日期")
    private LocalDate matValidEndDate;

    /**
     * 物料条码
     */
    @ExcelProperty("物料条码")
    @Schema(description = "物料条码")
    private String matBarcode;

    /**
     * 供应商编码
     */
    @ExcelProperty("供应商编码")
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @ExcelProperty("供应商名称")
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 入库时间
     */
    @ExcelProperty("入库时间")
    @Schema(description = "入库时间")
    private LocalDateTime inTime;

    /**
     * 库位编码
     */
    @ExcelProperty("库位编码")
    @Schema(description = "库位编码")
    private String siteCode;

    /**
     * 库位名称
     */
    @ExcelProperty("库位名称")
    @Schema(description = "库位名称")
    private String siteName;

    /**
     * 经办人
     */
    @ExcelProperty("经办人")
    @Schema(description = "经办人")
    private String handleBy;

    /**
     * 经办人姓名
     */
    @ExcelProperty("经办人姓名")
    @Schema(description = "经办人姓名")
    private String handleByName;

    /**
     * 发送部门
     */
    @ExcelProperty("发送部门")
    @Schema(description = "发送部门")
    private String sendingDeptCode;

    /**
     * 发送部门名称
     */
    @ExcelProperty("发送部门名称")
    @Schema(description = "发送部门名称")
    private String sendingDeptName;

    /**
     * 接收部门
     */
    @ExcelProperty("接收部门")
    @Schema(description = "接收部门")
    private String receiptDeptCode;

    /**
     * 接收部门名称
     */
    @ExcelProperty("接收部门名称")
    @Schema(description = "接收部门名称")
    private String receiptDeptName;

    /**
     * 质检状态
     */
    @ExcelProperty("质检状态")
    @Schema(description = "质检状态")
    private String inspectionStatus;

    /**
     * 物料明细类型 原材料、半成品、在制品
     */
    @ExcelProperty("物料明细类型")
    @Schema(description = "物料明细类型 原材料、半成品、在制品")
    private String matDetailType;

}