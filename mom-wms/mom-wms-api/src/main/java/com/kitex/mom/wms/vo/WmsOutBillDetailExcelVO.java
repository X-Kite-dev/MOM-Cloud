package com.kitex.mom.wms.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 出库单明细导出类
 *
 * @author kitex
 * @date 2025-1-9 16:12:09
 */
@Data
@Accessors(chain = true)
@Schema(description = "出库单明细导出类")
public class WmsOutBillDetailExcelVO {

    /**
     * 序号
     */
    @ColumnWidth(8)
    @ExcelProperty("序号")
    @Schema(description = "序号")
    private Integer rowNo;

    /**
     * 出库单号
     */
    @ColumnWidth(18)
    @ExcelProperty("出库单号")
    @Schema(description = "出库单号")
    private String billNo;

    /**
     * 出库通知单号
     */
    @ColumnWidth(18)
    @ExcelProperty("出库通知单号")
    @Schema(description = "出库通知单号")
    private String noticeId;

    /**
     * 状态
     */
    @ColumnWidth(8)
    @ExcelProperty("状态")
    @Schema(description = "状态")
    private String state;

    /**
     * 物料编码
     */
    @ColumnWidth(15)
    @ExcelProperty("物料编码")
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @ColumnWidth(15)
    @ExcelProperty("物料名称")
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 物料条码
     */
    @ColumnWidth(12)
    @ExcelProperty("物料条码")
    @Schema(description = "物料条码")
    private String matBarcode;

    /**
     * 物料批次
     */
    @ColumnWidth(12)
    @ExcelProperty("物料批次")
    @Schema(description = "物料批次")
    private String matBatch;

    /**
     * 规格型号
     */
    @ColumnWidth(12)
    @ExcelProperty("规格型号")
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 库位编码
     */
    @ColumnWidth(12)
    @ExcelProperty("库位编码")
    @Schema(description = "库位编码")
    private String siteCode;

    /**
     * 库位名称
     */
    @ColumnWidth(15)
    @ExcelProperty("库位名称")
    @Schema(description = "库位名称")
    private String siteName;

    /**
     * 包装规格
     */
    @ColumnWidth(12)
    @ExcelProperty("包装规格")
    @Schema(description = "包装规格")
    private String matPackSpec;

    /**
     * 包装数量
     */
    @ColumnWidth(12)
    @ExcelProperty("包装数量")
    @Schema(description = "包装数量")
    private String operateNum;

    /**
     * 出库数量
     */
    @ColumnWidth(12)
    @ExcelProperty("出库数量")
    @Schema(description = "出库数量")
    private String matNum;

    /**
     * 供应商编码
     */
    @ColumnWidth(15)
    @ExcelProperty("供应商编码")
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @ColumnWidth(15)
    @ExcelProperty("供应商名称")
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 容器编码
     */
    @ColumnWidth(15)
    @ExcelProperty("容器编码")
    @Schema(description = "容器编码")
    private String containerCode;

    /**
     * 是否质检;0否 1是
     */
    @ColumnWidth(12)
    @ExcelProperty("是否质检")
    @Schema(description = "是否质检")
    private String isQualityTest;

    /**
     * 质检结果
     */
    @ColumnWidth(12)
    @ExcelProperty("质检结果")
    @Schema(description = "质检结果")
    private String qualityResult;

    /**
     * 出库时间
     */
    @ColumnWidth(18)
    @ExcelProperty("出库时间")
    @Schema(description = "出库时间")
    private LocalDateTime outTime;

    /**
     * 生产日期
     */
    @ColumnWidth(12)
    @ExcelProperty("生产日期")
    @Schema(description = "生产日期")
    private LocalDate matProduceDate;

    /**
     * 有效期（天）
     */
    @ColumnWidth(18)
    @ExcelProperty("有效期（天）")
    @Schema(description = "有效期（天）")
    private Integer matValidPeriod;

    /**
     * 有效截至日期
     */
    @ColumnWidth(18)
    @ExcelProperty("有效截至日期")
    @Schema(description = "有效截至日期")
    private LocalDate matValidEndDate;

    /**
     * 车间名称
     */
    @ColumnWidth(12)
    @ExcelProperty("车间名称")
    @Schema(description = "车间名称")
    private String workshop;

}