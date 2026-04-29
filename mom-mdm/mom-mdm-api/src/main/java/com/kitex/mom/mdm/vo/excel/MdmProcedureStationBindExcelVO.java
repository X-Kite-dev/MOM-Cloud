package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 工序-工位绑定 excel 对应的实体
 *
 * @author kitex
 * @date 2025-09-10 14:33:49
 */
@Data
@ColumnWidth(30)
public class MdmProcedureStationBindExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @ExcelLine
    @ExcelIgnore
    private Long lineNum;

    @ExcelProperty("工序编码")
    @NotBlank(message = "工序编码为空")
    private String procedureCode;

    @ExcelProperty("工序名称")
    private String procedureName;

    @ExcelProperty("工位编码")
    @NotBlank(message = "工位编码不能为空")
    private String stationCode;

    @ExcelProperty("工位名称")
    private String stationName;

    @ExcelProperty("线体编码")
    private String lineCode;

    @ExcelProperty("线体名称")
    private String lineName;

    @ExcelProperty("车间编码")
    private String workshopCode;

    @ExcelProperty("车间名称")
    private String workshopName;

    @ExcelProperty("工厂编码")
    private String factoryCode;

    @ExcelProperty("工厂名称")
    private String factoryName;

    @ExcelProperty("状态")
    private String status;
}


