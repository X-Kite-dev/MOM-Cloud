package com.kitex.mom.wms.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 实时库存供应商级导出类
 *
 * @author kitex
 * @date 2025-1-10 10:57:25
 */
@Data
@Accessors(chain = true)
@Schema(description = "实时库存供应商级导出类")
public class WmsLiveStockBySupplierExcelVO {

    /**
     * 序号
     */
    @ColumnWidth(8)
    @ExcelProperty("序号")
    @Schema(description = "序号")
    private Integer rowNo;

    /**
     * 供应商编码
     */
    @ColumnWidth(18)
    @ExcelProperty("供应商编码")
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @ColumnWidth(18)
    @ExcelProperty("供应商名称")
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 物料编码
     */
    @ColumnWidth(18)
    @ExcelProperty("物料编码")
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @ColumnWidth(18)
    @ExcelProperty("物料名称")
    @Schema(description = "物料名称")
    private String matName;

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

}