package com.kitex.mom.plan.vo;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 计划需求 excel实体
 *
 * @author kitex
 * @date 2025-02-06 16:28:57
 */
@Data
@ColumnWidth(30)
public class PlnDemandMonthExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 行号
     */
    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    /**
     * 客户编码
     */
    @ExcelProperty("客户编码")
    @NotNull(message = "客户编码不能为空")
    private String customerCode;

    /**
     * 产品编码
     */
    @ExcelProperty("物料编码")
    @NotNull(message = "物料编码不能为空")
    private String productCode;

    /**
     * 总需求量
     */
    @ExcelProperty("总需求量")
    private BigDecimal totalDemandNum;

    /**
     * 可用量
     */
    @ExcelProperty("可用量")
    private BigDecimal availableNum;

    /**
     * 总缺料数量
     */
    @ExcelProperty("总缺料数量")
    private BigDecimal totalLackNum;

    /**
     * 月缺量
     */
    @ExcelProperty("月缺量")
    private BigDecimal monthDeficitNum;

    /**
     * 近三月用量
     */
    @ExcelProperty("近三月用量")
    private BigDecimal threeMonthNum;

    /**
     * 单月平均用量
     */
    @ExcelProperty("单月平均用量")
    private BigDecimal monthAverageNum;

    /**
     * 整箱数量
     */
    @ExcelProperty("整箱数量")
    private BigDecimal containerNum;

    /**
     * 需求月份
     */
    @ExcelProperty("计划年月")
    @NotNull(message = "计划年月不能为空")
    private String demandTime;
}