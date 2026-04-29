package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 库位excelVO
 *
 * @author kitex
 * @date 2025-02-21 14:08:01
 */
@Data
@ColumnWidth(30)
@Schema(description = "库位excelVO")
public class MdmWarehouseLocationExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 行号
     */
    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    /**
     * 编码
     */
    @ExcelProperty("编码")
    private String locationCode;

    /**
     * 名称
     */
    @ExcelProperty("名称")
    private String locationName;

    /**
     * 说明
     */
    @ExcelProperty(value = "说明")
    private String description;

    /**
     * 排序
     */
    @ExcelProperty(value = "排序")
    private Integer sort;

    /**
     * 状态
     */
    @ExcelProperty(value = "状态")
    private String status;

}