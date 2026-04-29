package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 厂房excel 对应的实体
 *
 * @author kitex
 * @date 2025-05-23 15:03:39
 */
@Data
@ColumnWidth(30)
public class MdmFactoryBuildingExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入时候回显行号
     */
    @ExcelLine
    @ExcelIgnore
    private Long lineNum;

    /**
     * 厂房名称
     */
    @NotBlank(message = "厂房名称不能为空")
    @ExcelProperty("厂房名称")
    private String name;

    /**
     * 厂房编码
     */
    @NotBlank(message = "厂房编码不能为空")
    @ExcelProperty("厂房编码")
    private String code;

    /**
     * 厂房地址
     */
    @ExcelProperty("厂房地址")
    private String address;

    /**
     * 备注
     */
    @ExcelProperty("备注")
    private String remark;

}
