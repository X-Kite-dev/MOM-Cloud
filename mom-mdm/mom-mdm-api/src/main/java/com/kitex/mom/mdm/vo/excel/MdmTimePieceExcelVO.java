package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.alibaba.excel.annotation.write.style.ContentRowHeight;
import com.alibaba.excel.annotation.write.style.HeadRowHeight;
import com.kitex.common.excel.annotation.ExcelLine;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 计时计件Excel导入导出VO
 *
 * @author kitex
 * @date 2025-09-04 09:10:56
 */
@Data
@ContentRowHeight(20)
@HeadRowHeight(25)
@ColumnWidth(30)
@Schema(description = "计时计件Excel导入导出VO")
public class MdmTimePieceExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入时候回显行号
     */
    @ExcelLine
    @ExcelIgnore
    private Long lineNum;

    @ExcelProperty("产品编码")
    @NotBlank(message = "产品编码不能为空")
    @Schema(description = "产品编码")
    private String matCode;

    @ExcelProperty("产品名称")
    @Schema(description = "产品名称")
    private String matName;

    @ExcelProperty("规格型号")
    @Schema(description = "规格型号")
    private String matSpec;

    @ExcelProperty("工序编码")
    @NotBlank(message = "工序编码不能为空")
    @Schema(description = "工序编码")
    private String procedureCode;

    @ExcelProperty("工序名称")
    @Schema(description = "工序名称")
    private String procedureName;

    @ExcelProperty("计费类型")
    @NotBlank(message = "计费类型不能为空")
    @Schema(description = "计费类型")
    private String type;

    @ExcelProperty("费用(元)")
    @Schema(description = "费用(元)")
    private BigDecimal price;

    @ExcelProperty("状态")
    @Schema(description = "状态 (启用, 禁用)")
    private String status;

    @ExcelProperty("备注")
    @Schema(description = "备注")
    private String remark;
}