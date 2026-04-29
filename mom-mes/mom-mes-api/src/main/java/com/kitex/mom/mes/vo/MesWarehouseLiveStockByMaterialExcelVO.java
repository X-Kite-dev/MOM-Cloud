package com.kitex.mom.mes.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 实时库存物料级导出类
 *
 * @author kitex
 * @date 2025-1-10 10:57:25
 */
@Data
@Accessors(chain = true)
@Schema(description = "实时库存物料级导出类")
public class MesWarehouseLiveStockByMaterialExcelVO {

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
     * 规格型号
     */
    @ColumnWidth(12)
    @ExcelProperty("规格型号")
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 数量
     */
    @ColumnWidth(12)
    @ExcelProperty("数量")
    @Schema(description = "数量")
    private BigDecimal num;

    /**
     * 单位
     */
    @ColumnWidth(12)
    @ExcelProperty("单位")
    @Schema(description = "单位")
    private String matUnit;

}