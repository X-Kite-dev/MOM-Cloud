package com.kitex.mom.mdm.vo.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 工艺路线
 *
 * @author kitex
 * @date 2024-10-30 14:36:59
 */
@Data
@ColumnWidth(30)
public class MdmProcessRouteExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入时候回显行号
     */
    @ExcelLine
    @ExcelIgnore
    private Long lineNum;

    /**
     * 工艺路线编码
     */
    @NotBlank(message = "工艺路线编码不能为空")
    @ExcelProperty("工艺路线编码")
    private String routeCode;

    /**
     * 工艺路线名称
     */
    @NotBlank(message = "工艺路线名称不能为空")
    @ExcelProperty("工艺路线名称")
    private String routeName;

    /**
     * 产品编码
     */
    @ExcelProperty("产品编码")
    @NotBlank(message = "产品编码不能为空")
    private String productCode;

    /**
     * 产品名称
     */
    @ExcelProperty("产品名称")
    private String productName;

    /**
     * 加工车间
     */
    @ExcelProperty("加工车间名称")
    private String workshopName;

    /**
     * 产线编码
     */
    @NotBlank(message = "产线编码不能为空")
    @ExcelProperty("产线编码")
    private String productLineCode;

    /**
     * 产线名称
     */
    @ExcelProperty("产线名称")
    private String productLineName;

    /**
     * bom版本
     */
    @NotBlank(message = "bom版本不能为空")
    @ExcelProperty(value = "bom版本")
    private String bomVersion;

    /**
     * 生产速率
     */
    @ExcelProperty(value = "生产速率")
    private BigDecimal productRate;

    /**
     * 备注
     */
    @ExcelProperty("备注")
    private String remark;

}