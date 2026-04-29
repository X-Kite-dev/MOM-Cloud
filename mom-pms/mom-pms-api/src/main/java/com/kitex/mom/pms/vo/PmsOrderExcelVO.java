package com.kitex.mom.pms.vo;

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
 * 采购订单 excel实体
 *
 * @author kitex
 * @date 2025-01-10 09:35:21
 */
@Data
@ColumnWidth(30)
public class PmsOrderExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 行号
     */
    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    /**
     * 采购订单号
     */
    @ExcelProperty("采购订单号")
    private String pmsOrderNo;

    /**
     * 供应商编码
     */
    @ExcelProperty("供应商编码")
    @NotNull(message = "供应商编码不能为空")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @ExcelProperty("供应商名称")
    private String supplierName;

    /**
     * 订单状态
     */
    @ExcelProperty("订单状态")
    private String status;

    /**
     * 物料编码
     */
    @ExcelProperty("物料编码")
    @NotNull(message = "物料编码不能为空")
    private String matCode;
    /**
     * 物料名称
     */
    @ExcelProperty("物料名称")
    private String matName;
    /**
     * 物料单位
     */
    @ExcelProperty("物料单位")
    private String matUnit;
    /**
     * 物料规格
     */
    @ExcelProperty("物料规格")
    private String matSpec;
    /**
     * 采购数量
     */
    @ExcelProperty("采购数量")
    @NotNull(message = "采购数量不能为空")
    private Double orderNum;
    /**
     * 要货时间
     */
    @ExcelProperty(value = "要货时间")
    @NotNull(message = "要货时间不能为空")
    private LocalDate deliveryTime;
    /**
     * 到货数量
     */
    @ExcelProperty("到货数量")
    private Double arrivalNum;
    /**
     * 采购单价
     */
    @ExcelProperty("采购单价")
    @NotNull(message = "采购单价不能为空")
    private BigDecimal price;
}