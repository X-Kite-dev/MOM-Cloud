package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 仓库建模excel
 *
 * @author kitex
 * @date 2025-09-23 11:11:47
 */
@Data
@ColumnWidth(30)
public class MdmWarehouseModelExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入回显行号
     */
    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    /**
     * 父级id
     */
    @ExcelIgnore
    private Long parentId;

    /**
     * 层级
     */
    @ExcelIgnore
    private Integer locationLevel;

    /**
     * 工厂编码
     */
    @NotBlank(message = "工厂编码不能为空")
    @ExcelProperty(value = "工厂编码")
    private String factoryCode;

    /**
     * 编码
     */
    @NotBlank(message = "父编码不能为空")
    @ExcelProperty(value = "父编码")
    private String parentCode;

    /**
     * 编码
     */
    @NotBlank(message = "编码不能为空")
    @ExcelProperty(value = "编码")
    private String code;

    /**
     * 名称
     */
    @NotBlank(message = "名称不能为空")
    @ExcelProperty(value = "名称")
    private String name;

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
