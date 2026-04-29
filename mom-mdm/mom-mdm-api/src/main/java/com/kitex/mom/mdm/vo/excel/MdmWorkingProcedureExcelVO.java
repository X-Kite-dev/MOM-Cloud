package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 工序excel 对应的实体
 *
 * @author kitex
 * @date 2024-11-05 11:21:48
 */
@Data
@ColumnWidth(30)
public class MdmWorkingProcedureExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入时候回显行号
     */
    @ExcelLine
    @ExcelIgnore
    private Long lineNum;

    /**
     * 工序编码
     */
    @NotBlank(message = "工序编码不能为空")
    @ExcelProperty("工序编码")
    private String processCode;

    /**
     * 工序名称
     */
    @NotBlank(message = "工序名称不能为空")
    @ExcelProperty("工序名称")
    private String processName;

    /**
     * 作业名称
     */
    @ExcelProperty("作业名称")
    private String operationName;

    /**
     * 作业说明
     */
    @ExcelProperty("作业说明")
    private String operationDescription;

}
