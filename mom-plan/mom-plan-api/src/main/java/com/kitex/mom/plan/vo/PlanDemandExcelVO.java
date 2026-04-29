package com.kitex.mom.plan.vo;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 计划需求 excel实体
 *
 * @author kitex
 * @date 2025-02-06 16:28:57
 */
@Data
@ColumnWidth(30)
public class PlanDemandExcelVO implements Serializable {

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
    @ExcelProperty("产品编码")
    @NotNull(message = "产品编码不能为空")
    private String productCode;

    /**
     * 预排需求量
     */
    @ExcelProperty("预排需求量")
    private BigDecimal predictDemandNum;

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
     * 已提供比例
     */
    @ExcelProperty("已提供比例")
    private String providedRatio;

    /**
     * 需求时间
     */
    @ExcelProperty(value = "需求时间")
    @NotNull(message = "需求时间不能为空")
    private LocalDate demandTime;

    /**
     * 发货
     */
    @ExcelProperty("发货")
    private String dailyDispatch;
}