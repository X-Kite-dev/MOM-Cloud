package com.kitex.mom.plan.vo;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.excel.annotation.ExcelLine;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * @program: mom-cloud
 * @description: 零件计划导出类
 * @author: qinghuan
 * @create: 2024-12-12 14:07
 **/
@Data
@ColumnWidth(30)
public class PlnPartsPlanExcelVO implements Serializable {

    /**
     * 行号
     */
    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    /**
     * 生产订单号
     */
    @Schema(description = "生产订单号")
    @ExcelProperty("生产订单号")
    private String productionOrder;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
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

    /**
     * 开始时间
     */
    @Schema(description = "开始时间")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @ExcelProperty("开始时间")
    private LocalDateTime planStartDate;

    /**
     * 日需求量
     */
    @Schema(description = "日需求量")
    @ExcelProperty("日需求量")
    private BigDecimal dayNeedNum;

    /**
     * 需求日期
     */
    @Schema(description = "需求日期")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @ExcelProperty("需求日期")
    private LocalDateTime planEndDate;

    /**
     * 需求数量
     */
    @Schema(description = "需求数量")
    @ExcelProperty("需求数量")
    private BigDecimal needNum;

    /**
     * 外协数量
     */
    @Schema(description = "外协数量")
    @ExcelProperty("外协数量")
    private BigDecimal outsourcingNum;

    /**
     * 厂内现存
     */
    @Schema(description = "厂内现存")
    @ExcelProperty("厂内现存")
    private BigDecimal insideNum;

    /**
     * 总排产量
     */
    @Schema(description = "总排产量")
    @ExcelProperty("总排产量")
    private BigDecimal totalOutputNum;

    /**
     * 计划类型
     */
    @Schema(description = "计划类型")
    @ExcelProperty("计划类型")
    private String planType;

    /**
     * 审批状态
     */
    @Schema(description = "审批状态")
    @ExcelProperty("审批状态")
    private String approvalStatus;

    /**
     * 计划状态
     */
    @Schema(description = "计划状态")
    @ExcelProperty("计划状态")
    private String planState;

    /**
     * 部门编码
     */
    @Schema(description = "部门编码")
    @ExcelProperty("部门编码")
    private String deptCode;

}
