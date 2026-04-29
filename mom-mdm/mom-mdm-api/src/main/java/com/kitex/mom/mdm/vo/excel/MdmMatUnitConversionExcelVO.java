package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 物料单位转换规则
 *
 * @author kitex
 * @date 2024-12-12 10:59:24
 */
@Data
@ColumnWidth(30)
public class MdmMatUnitConversionExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

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
     * 非标准单位
     */
    @ExcelProperty("非标准单位")
    @Schema(description = "非标准单位")
    private String nonStandardUnit;

    /**
     * 比率
     */
    @ExcelProperty("比率")
    @Schema(description = "比率")
    private String ratio;

    /**
     * 标准单位
     */
    @ExcelProperty("标准单位")
    @Schema(description = "标准单位")
    private String standardUnit;

}