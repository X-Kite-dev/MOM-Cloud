package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 产线excel 对应的实体
 *
 * @author kitex
 * @date 2025-05-23 15:03:39
 */
@Data
@ColumnWidth(30)
public class MdmProductLineExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入时候回显行号
     */
    @ExcelLine
    @ExcelIgnore
    private Long lineNum;

    /**
     * 产线名称
     */
    @NotBlank(message = "产线名称不能为空")
    @ExcelProperty("产线名称")
    private String name;

    /**
     * 产线编码
     */
    @NotBlank(message = "产线编码不能为空")
    @ExcelProperty("产线编码")
    private String code;

    /**
     * 车间编码
     */
    @ExcelProperty("所属车间编码")
    private String workshopCode;

    /**
     * 车间名称
     */
    @NotBlank(message = "所属车间名称不能为空")
    @ExcelProperty("所属车间名称")
    private String workshopName;

    /**
     * 厂房编码
     */
    @ExcelProperty("厂房编码")
    private String factoryCode;

    /**
     * 厂房名称
     */
    @ExcelProperty("厂房名称")
    private String factoryName;

    /**
     * 工作中心编码列表
     */
    @ExcelProperty("工作中心编码")
    private String workCenterCodes;

    /**
     * 工作中心名称列表
     */
    @ExcelProperty("工作中心名称")
    @NotBlank(message = "工作中心名称不能为空")
    private String workCenterNames;

    /**
     * 备注
     */
    @ExcelProperty("备注")
    private String remark;

}
