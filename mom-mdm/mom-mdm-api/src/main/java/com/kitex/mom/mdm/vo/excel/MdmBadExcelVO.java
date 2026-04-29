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
 *
 */
@Data
@ColumnWidth(30)
@Schema(description = "车间Excel数据类")
public class MdmBadExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 行号
     */
    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    @NotBlank(message = "不良编码不能为空")
    @ExcelProperty(value = "不良编码")
    @Schema(description = "不良编码")
    private String badCode;

    @NotBlank(message = "不良名称不能为空")
    @ExcelProperty(value = "不良名称")
    @Schema(description = "不良名称")
    private String badName;

    @NotBlank(message = "不良类别不能为空")
    @ExcelProperty(value = "不良类别")
    @Schema(description = "不良类型 分类、不良品项")
    private String badType;

    @ExcelProperty(value = "上级分类编码")
    @Schema(description = "父编码")
    private String parentCode;

    @ExcelProperty(value = "备注")
    @Schema(description = "备注")
    private String remark;

    private String status;

}
