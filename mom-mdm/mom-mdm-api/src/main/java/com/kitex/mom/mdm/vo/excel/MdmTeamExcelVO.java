package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 班组excel
 *
 * @author kitex
 * @date 2025-02-17 10:55:32
 */
@Data
@ColumnWidth(30)
@Schema(description = "班组excel")
public class MdmTeamExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入回显行号
     */
    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    /**
     * 班组名称
     */
    @NotBlank(message = "班组名称不能为空")
    @ExcelProperty(value = "班组名称")
    private String name;

    /**
     * 班组编码
     */
    @NotBlank(message = "班组编码不能为空")
    @ExcelProperty(value = "班组编码")
    private String code;

    /**
     * 类型
     */
    @NotBlank(message = "类型不能为空")
    @ExcelProperty(value = "类型")
    private String type;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;

}