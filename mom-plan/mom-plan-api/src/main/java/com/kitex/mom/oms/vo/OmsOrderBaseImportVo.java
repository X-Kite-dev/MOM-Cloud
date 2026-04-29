package com.kitex.mom.oms.vo;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.excel.annotation.ExcelLine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 订单输入导入
 */
@Data
public class OmsOrderBaseImportVo {

    /**
     * 导入回显行号
     */
    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    /**
     * 组号
     */
    @NotBlank(message = "组号不能为空")
    @ExcelProperty(value = "组号")
    private String groupCode;

    /**
     * 产品编码
     */
    @NotBlank(message = "产品编码不能为空")
    @ExcelProperty(value = "产品编码")
    private String productCode;

    /**
     * 客户编码
     */
    @NotBlank(message = "客户编码不能为空")
    @ExcelProperty(value = "客户编码")
    private String customerCode;

    /**
     * 订单类型
     */
    @NotBlank(message = "订单类型不能为空")
    @ExcelProperty(value = "订单类型")
    private String orderType;

    /**
     * 交付时间
     */
    @NotNull(message = "交付时间不能为空")
    @ExcelProperty(value = "交付时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    private LocalDate deliverTime;

    /**
     * 交付数量
     */
    @NotNull(message = "交付数量不能为空")
    @ExcelProperty(value = "交付数量")
    private BigDecimal deliverNum;

    /**
     * 备注
     */
    @ExcelProperty(value = "备注")
    private String remark;


}