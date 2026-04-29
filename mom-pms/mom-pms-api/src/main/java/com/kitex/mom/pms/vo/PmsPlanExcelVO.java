package com.kitex.mom.pms.vo;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 采购计划 excel实体
 *
 * @author kitex
 * @date 2025-01-10 09:11:38
 */
@Data
@ColumnWidth(30)
public class PmsPlanExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 行号
     */
    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    /**
     * 采购计划号
     */
    @ExcelProperty("采购计划号")
    private String pmsPlanNo;

    /**
     * 订单号
     */
    @ExcelProperty("订单号")
    private String orderNo;

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
    private Double pmsNum;

    /**
     * 状态
     */
    @ExcelProperty("状态")
    private String status;

    /**
     * 要货时间
     */
    @ExcelProperty(value = "要货时间")
    @NotNull(message = "要货时间不能为空")
    private LocalDate planDeliveryTime;

    /**
     * 下单数量
     */
    @ExcelProperty("下单数量")
    private Double planOrderNum;

    /**
     * 到货数量
     */
    @ExcelProperty("到货数量")
    private Double planArrivalNum;
}