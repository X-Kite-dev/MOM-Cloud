package com.kitex.mom.plan.vo;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;


/**
 * 客户订单excel VO
 *
 * @author kitex
 * @date 2025-03-07 09:05:28
 */
@Data
@ColumnWidth(30)
@Schema(description = "客户订单excel VO")
public class PlnProductDemandExcelVO implements Serializable {

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
    @Schema(description = "客户编码")
    @NotNull(message = "客户编码不能为空")
    @ExcelProperty("客户编码")
    private String customerCode;

    /**
     * 客户名称
     */
    @Schema(description = "客户名称")
    @ExcelProperty("客户名称")
    private String customerName;

    /**
     * 销售单号
     */
    @Schema(description = "销售单号")
    @ExcelProperty("销售单号")
    private String saleOrderCode;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    @NotNull(message = "产品编码不能为空")
    @ExcelProperty("产品编码")
    private String productCode;

    /**
     * 产品名称
     */
    @Schema(description = "产品名称")
    @ExcelProperty("产品名称")
    private String productName;

    /**
     * 产品规格
     */
    @Schema(description = "产品规格")
    @ExcelProperty("产品规格")
    private String specification;

    @Schema(description = "需求领料仓库编码")
    @NotNull(message = "需求领料仓库编码不能为空")
    @ExcelProperty("需求领料仓库编码")
    private String demandWarehouseCode;

    @Schema(description = "需求领料仓库名称")
    @ExcelProperty("需求领料仓库名称")
    private String demandWarehouseName;

    /**
     * 需求日期
     */
    @Schema(description = "需求日期")
    @NotNull(message = "需求日期不能为空")
    @ExcelProperty("需求日期")
    private LocalDate planEndDate;

    /**
     * 需求数量
     */
    @Schema(description = "需求数量")
    @NotNull(message = "需求数量不能为空")
    @ExcelProperty("需求数量")
    private BigDecimal needNum;

    @Schema(description = "同一需求日期时间桶内的MRP库存分配优先级，1-999且数值越大越优先")
    @ExcelProperty("MRP优先级")
    private Integer mrpPriority;

    /**
     * 计划状态 - 0: 未接受, 1: 已接收
     */
    @Schema(description = "计划状态 - 0: 未接受, 1: 已接收")
    @ExcelProperty("计划状态")
    private String planState;

}
