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
 * @program: mom-cloud
 * @description: 设备excel数据类
 * @author: qinghuan
 * @create: 2024-10-28 21:34
 **/
@Data
@ColumnWidth(30)
@Schema(description = "设备excel数据类")
public class MdmDeviceExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入回显行号
     */
    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    /**
     * 设备编码
     */
    @ExcelProperty(value = "设备编码")
    @Schema(description = "设备编码")
    @NotBlank(message = "设备编码不能为空")
    private String deviceCode;

    /**
     * 设备名称
     */
    @Schema(description = "设备名称")
    @ExcelProperty(value = "设备名称")
    @NotBlank(message = "设备名称不能为空")
    private String deviceName;

    /**
     * 设备分类编码
     */
    @Schema(description = "设备分类编码")
    @ExcelProperty(value = "设备分类编码")
    @NotBlank(message = "设备分类编码不能为空")
    private String classCode;

    /**
     * 设备分类名称
     */
    @Schema(description = "设备分类名称")
    @ExcelProperty(value = "设备分类名称")
    private String className;

    /**
     * 设备位置
     */
    @Schema(description = "设备位置")
    @ExcelProperty(value = "设备位置")
    private String location;

    /**
     * 状态 (1=启用, 0=禁用)
     */
    @Schema(description = "状态 (1=启用, 0=禁用)")
    @ExcelProperty(value = "状态 (1=启用, 0=禁用)")
    private String status;

}
