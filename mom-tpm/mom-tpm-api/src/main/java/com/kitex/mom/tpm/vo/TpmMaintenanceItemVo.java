package com.kitex.mom.tpm.vo;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.kitex.common.excel.annotation.ExcelLine;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 保养项目VO
 */
@Data
@Schema(description = "保养项目VO")
public class TpmMaintenanceItemVo {

    /**
     * 导入回显行号
     */
    @ExcelIgnore
    @ExcelLine
    private Long lineNum;


    /**
     * 编码
     */
    @ExcelProperty(value = "编码")
    @NotBlank(message = "项目编码不能为空")
    @Schema(description = "编码")
    private String code;

    /**
     * 名称
     */
    @ExcelProperty(value = "名称")
    @NotBlank(message = "项目名称不能为空")
    @Schema(description = "名称")
    private String name;

    /**
     * 保养方法
     */
    @ExcelProperty(value = "保养方法")
    @NotBlank(message = "保养方法不能为空")
    @Schema(description = "保养方法")
    private String checkMethod;

    /**
     * 保养工具
     */
    @ExcelProperty(value = "保养工具")
    @NotBlank(message = "保养工具不能为空")
    @Schema(description = "保养工具")
    private String checkTool;

    /**
     * 判断类型0直接确认 1填写值
     */
    @ExcelProperty(value = "判断类型")
    @NotBlank(message = "判断类型不能为空")
    @Schema(description = "判断类型0直接确认 1填写值")
    private String judgeType;

    /**
     * 标准上限
     */
    @ExcelProperty(value = "标准上限")
    @Schema(description = "标准上限")
    private BigDecimal standardMax;

    /**
     * 标准下限
     */
    @ExcelProperty(value = "标准下限")
    @Schema(description = "标准下限")
    private BigDecimal standardMin;

}