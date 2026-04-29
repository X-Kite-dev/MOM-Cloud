package com.kitex.mom.wms.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 实时库存明细导出类
 *
 * @author kitex
 * @date 2025-1-10 10:57:25
 */
@Data
@Accessors(chain = true)
@Schema(description = "实时库存明细导出类")
public class WmsLiveStockDetailExcelVO {

    /**
     * 序号
     */
    @ColumnWidth(8)
    @ExcelProperty("序号")
    @Schema(description = "序号")
    private Integer rowNo;

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
     * 物料批次
     */
    @ColumnWidth(15)
    @ExcelProperty("物料批次")
    @Schema(description = "物料批次")
    private String matBatch;

    /**
     * 数量
     */
    @ColumnWidth(12)
    @ExcelProperty("数量")
    @Schema(description = "数量")
    private Double matNum;

    /**
     * 单位
     */
    @ColumnWidth(12)
    @ExcelProperty("单位")
    @Schema(description = "单位")
    private String matUnit;

    /**
     * 生产日期
     */
    @ColumnWidth(15)
    @ExcelProperty("生产日期")
    @Schema(description = "生产日期")
    private LocalDate matProduceDate;

    /**
     * 有效期
     */
    @ColumnWidth(15)
    @ExcelProperty("有效期(天)")
    @Schema(description = "有效期")
    private Integer matValidPeriod;

    /**
     * 有效截至日期
     */
    @ColumnWidth(18)
    @ExcelProperty("有效截至日期")
    @Schema(description = "有效截至日期")
    private LocalDate matValidEndDate;

    /**
     * 规格型号
     */
    @ColumnWidth(12)
    @ExcelProperty("规格型号")
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 供应商
     */
    @ColumnWidth(12)
    @ExcelProperty("供应商")
    @Schema(description = "供应商")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @ColumnWidth(18)
    @ExcelProperty("供应商名称")
    @Schema(description = "供应商名称")
    private String supplierName;

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
     * 容器编码
     */
    @ColumnWidth(15)
    @ExcelProperty("容器编码")
    @Schema(description = "容器编码")
    private String containerCode;

    /**
     * 入库时间
     */
    @ColumnWidth(18)
    @ExcelProperty("入库时间")
    @Schema(description = "入库时间")
    private LocalDateTime inTime;

    /**
     * 包装规格
     */
    @ColumnWidth(12)
    @ExcelProperty("包装规格")
    @Schema(description = "包装规格")
    private String matPackSpec;

    /**
     * 仓库编码
     */
    @ColumnWidth(12)
    @ExcelProperty("仓库编码")
    @Schema(description = "仓库编码")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @ColumnWidth(12)
    @ExcelProperty("仓库名称")
    @Schema(description = "仓库名称")
    private String warehouseName;

    /**
     * 质检结果
     */
    @ColumnWidth(12)
    @ExcelProperty("质检结果")
    @Schema(description = "质检结果")
    private String qualityResult;

    /**
     * 状态 是否被锁定;0否 1是
     */
    @ColumnWidth(12)
    @ExcelProperty("是否锁定")
    @Schema(description = "是否锁定")
    private String status;

}