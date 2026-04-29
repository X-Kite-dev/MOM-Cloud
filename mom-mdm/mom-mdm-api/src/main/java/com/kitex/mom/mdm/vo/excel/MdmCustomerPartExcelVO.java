package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 客户零件号excel 对应的实体
 *
 * @author kitex
 * @date 2024-11-07 14:05:04
 */
@Data
@ColumnWidth(30)
public class MdmCustomerPartExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入时候回显行号
     */
    @ExcelLine
    @ExcelIgnore
    private Long lineNum;

    /**
     * 零件号
     */
    @NotBlank(message = "零件号不能为空")
    @ExcelProperty("零件号")
    private String partNumber;

    /**
     * 客户编码
     */
    @NotBlank(message = "客户编码不能为空")
    @ExcelProperty("客户编码")
    private String customerCode;

    /**
     * 客户名称
     */
    @ExcelProperty("客户名称")
    private String customerName;

    /**
     * 物料编码
     */
    @NotBlank(message = "物料编码不能为空")
    @ExcelProperty("物料编码")
    private String materialCode;

    /**
     * 物料名称
     */
    @ExcelProperty("物料名称")
    private String materialName;

}
