package com.kitex.mom.wms.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 拣货区导出类
 *
 * @author kitex
 * @date 2025-01-21 15:38:39
 */
@Data
@Schema(description = "拣货区导出类")
public class WmsPickingAreaExcelVO {

    /**
     * 序号
     */
    @ExcelProperty("序号")
    @ColumnWidth(8)
    @Schema(description = "序号")
    private Integer rowNo;

    /**
     * 出库单号
     */
    @ExcelProperty("出库单号")
    @ColumnWidth(18)
    @Schema(description = "出库单号")
    private String billNo;

    /**
     * 物料编码
     */
    @ExcelProperty("物料编码")
    @ColumnWidth(18)
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @ExcelProperty("物料名称")
    @ColumnWidth(18)
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 规格型号
     */
    @ExcelProperty("规格型号")
    @ColumnWidth(18)
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 物料批次
     */
    @ExcelProperty("物料批次")
    @ColumnWidth(18)
    @Schema(description = "物料批次")
    private String matBatch;

    /**
     * 数量
     */
    @ExcelProperty("数量")
    @ColumnWidth(18)
    @Schema(description = "数量")
    private Double matNum;

    /**
     * 单位
     */
    @ExcelProperty("单位")
    @ColumnWidth(18)
    @Schema(description = "单位")
    private String matUnit;

    /**
     * 供应商编码
     */
    @ExcelProperty("供应商编码")
    @ColumnWidth(18)
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @ExcelProperty("供应商名称")
    @ColumnWidth(18)
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 仓库编码
     */
    @ExcelProperty("仓库编码")
    @ColumnWidth(18)
    @Schema(description = "仓库编码")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @ExcelProperty("仓库名称")
    @ColumnWidth(18)
    @Schema(description = "仓库名称")
    private String warehouseName;

    /**
     * 库位编码
     */
    @ExcelProperty("库位编码")
    @ColumnWidth(18)
    @Schema(description = "库位编码")
    private String siteCode;

    /**
     * 库存名称
     */
    @ExcelProperty("库存名称")
    @ColumnWidth(18)
    @Schema(description = "库存名称")
    private String siteName;

    /**
     * 容器编码
     */
    @ExcelProperty("容器编码")
    @ColumnWidth(18)
    @Schema(description = "容器编码")
    private String containerCode;

    /**
     * 入库时间
     */
    @ExcelProperty("入库时间")
    @ColumnWidth(18)
    @Schema(description = "入库时间")
    private LocalDateTime inTime;

    /**
     * 生产日期
     */
    @ExcelProperty("生产日期")
    @ColumnWidth(18)
    @Schema(description = "生产日期")
    private LocalDate matProduceDate;

    /**
     * 有效期
     */
    @ExcelProperty("有效期")
    @ColumnWidth(18)
    @Schema(description = "有效期")
    private Integer matValidPeriod;

    /**
     * 有效截至日期
     */
    @ExcelProperty("有效截至日期")
    @ColumnWidth(18)
    @Schema(description = "有效截至日期")
    private LocalDate matValidEndDate;

    /**
     * 质检结果
     */
    @ExcelProperty("质检结果")
    @ColumnWidth(18)
    @Schema(description = "质检结果")
    private String qualityResult;

    /**
     * 包装规格
     */
    @ExcelProperty("包装规格")
    @ColumnWidth(18)
    @Schema(description = "包装规格")
    private String matPackSpec;

    /**
     * 物料分组
     */
    @ExcelProperty("物料分组")
    @ColumnWidth(18)
    @Schema(description = "物料分组")
    private String matGroup;

    /**
     * 物料分组名称
     */
    @ExcelProperty("物料分组名称")
    @ColumnWidth(18)
    @Schema(description = "物料分组名称")
    private String matGroupName;

}