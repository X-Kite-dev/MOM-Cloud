package com.kitex.mom.pms.vo;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 采购异常 excel实体
 *
 * @author kitex
 * @date 2025-01-10 09:38:09
 */
@Data
@ColumnWidth(30)
public class PmsWarningExcelVO implements Serializable {

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
    private Long orderNo;

    /**
     * 物料编码
     */
    @ExcelProperty("物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @ExcelProperty("物料名称")
    private String matName;

    /**
     * 物料规格
     */
    @ExcelProperty("物料规格")
    private String matSpec;

    /**
     * 采购数量
     */
    @ExcelProperty("采购数量")
    private Double pmsNum;

    /**
     * 物料单位
     */
    @ExcelProperty("物料单位")
    private String matUnit;

    /**
     * 要货时间
     */
    @ExcelProperty(value = "要货时间")
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

    /**
     * 异常类型（0 延期到货 1 质检不合格）
     */
    @ExcelProperty("异常类型（0 延期到货 1 质检不合格）")
    private String type;

    /**
     * 异常原因
     */
    @ExcelProperty("异常原因")
    private String reason;

    /**
     * 异常确认状态
     */
    @ExcelProperty("异常确认状态")
    private String status;
}