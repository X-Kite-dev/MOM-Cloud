package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 工作中心excel 对应的实体
 *
 * @author kitex
 * @date 2024-11-04 17:42:19
 */
@Data
@ColumnWidth(30)
public class MdmWorkCenterExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入时候回显行号
     */
    @ExcelLine
    @ExcelIgnore
    private Long lineNum;

    /**
     * 工作中心编码
     */
    @NotBlank(message = "工作中心编码不能为空")
    @ExcelProperty("工作中心编码")
    private String code;

    /**
     * 工作中心名称
     */
    @NotBlank(message = "工作中心名称不能为空")
    @ExcelProperty("工作中心名称")
    private String name;

    /**
     * 产线编码
     */
    @ExcelProperty("产线编码")
    private String productLineCode;

    /**
     * 产线名称
     */
    @NotBlank(message = "产线名称不能为空")
    @ExcelProperty("产线名称")
    private String productLineName;

    /**
     * 设备编码
     */
    @ExcelProperty("设备编码")
    private String deviceCode;

    /**
     * 设备名称
     */
    @ExcelProperty("设备名称")
    private String deviceName;

    /**
     * 可用状态
     */
    @ExcelProperty("可用状态(启用/停用)")
    private String availableStatus;

    /**
     * 工作状态
     */
    @ExcelProperty("工作状态(使用中/停止)")
    private String workingStatus;

}
