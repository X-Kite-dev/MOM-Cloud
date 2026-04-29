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
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * @program: mom-cloud
 * @description: BOM数据类
 * @author: qinghuan
 * @create: 2024-10-30 16:01
 **/
@Data
@ColumnWidth(30)
@Schema(description = "BOMExcel数据类")
public class MdmBomExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导出回显号
     */
    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    /**
     * 层级
     */
    @ExcelProperty(value = "层级")
    @NotBlank(message = "层级不能为空")
    private String level;

    /**
     * 版本
     */
    @ExcelProperty(value = "BOM版本")
    @NotBlank(message = "版本不能为空")
    private String version;

    /**
     * 产品编码
     */
    @ExcelProperty(value = "产品编码")
    @Schema(description = "产品编码")
    @NotBlank(message = "产品编码不能为空")
    private String productCode;

    /**
     * 产品名称
     */
    @ExcelProperty(value = "产品名称")
    @Schema(description = "产品名称")
    private String productName;

    /**
     * 单位
     */
    @Schema(description = "单位")
    @ExcelIgnore
    private String unit;

    /**
     * 物料属性
     */
    @Schema(description = "物料属性")
    @ExcelIgnore
    private String materialAttribute;

    /**
     * 数量
     */
    @ExcelProperty(value = "数量")
    private BigDecimal quantity;

    /**
     * 开始日期
     */
    @ExcelProperty(value = "开始日期")
    @Schema(description = "开始日期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    /**
     * 截止日期
     */
    @ExcelProperty(value = "截止日期")
    @Schema(description = "截止日期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

}
