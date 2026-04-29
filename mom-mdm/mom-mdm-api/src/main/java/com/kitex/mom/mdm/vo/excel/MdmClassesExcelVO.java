package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.excel.annotation.ExcelLine;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalTime;

/**
 * @program: mom-cloud
 * @description: 工作日历excel数据类
 * @author: qinghuan
 * @create: 2024-11-02 14:27
 **/
@Data
@ColumnWidth(30)
public class MdmClassesExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    /**
     * 班次编码
     */
    @NotBlank(message = "班次编码不能为空")
    @Schema(description = "班次编码")
    @ExcelProperty(value = "班次编码")
    private String shiftCode;

    /**
     * 班次名称
     */
    @NotBlank(message = "班次名称不能为空")
    @Schema(description = "班次名称")
    @ExcelProperty(value = "班次名称")
    private String shiftName;

    /**
     * 开始时间
     */
    @NotBlank(message = "开始时间不能为空")
    @Schema(description = "开始时间")
    @JsonFormat(pattern = "HH:mm:ss")
    @ExcelProperty(value = "开始时间(HH:mm:ss)")
    private LocalTime startTime;

    /**
     * 结束时间
     */
    @NotBlank(message = "结束时间不能为空")
    @Schema(description = "结束时间")
    @JsonFormat(pattern = "HH:mm:ss")
    @ExcelProperty(value = "结束时间(HH:mm:ss)")
    private LocalTime endTime;

    /**
     * 状态 (1=启用, 0=禁用)
     */
    @Schema(description = "状态 (1=启用, 0=禁用)")
    @ExcelProperty(value = "状态 (1=启用, 0=禁用)")
    private Integer status;

}
