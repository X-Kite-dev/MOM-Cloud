package com.kitex.mom.pms.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 采购收料单
 *
 * @author kitex
 * @date 2025-01-22 11:34:35
 */
@Data
@Schema(description = "采购收料单")
public class WmsPurchaseReceiptExcelVO {

    /**
     * 序号
     */
    @ExcelProperty("序号")
    @ColumnWidth(8)
    @Schema(description = "序号")
    private Integer rowNo;

    /**
     * 采购收料单号
     */
    @ExcelProperty("采购收料单号")
    @ColumnWidth(18)
    @Schema(description = "采购收料单号")
    private String billNo;

    /**
     * 采购订单号
     */
    @ExcelProperty("采购订单号")
    @ColumnWidth(18)
    @Schema(description = "采购订单号")
    private String pmsOrderNo;

    /**
     * 供应商编码
     */
    @ExcelProperty("供应商编码")
    @ColumnWidth(18)
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @ExcelProperty("供应商名称")
    @ColumnWidth(18)
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 制单时间
     */
    @ExcelProperty("制单时间")
    @ColumnWidth(18)
    @Schema(description = "制单时间")
    private LocalDateTime createTime;

    /**
     * 制单人
     */
    @ExcelProperty("制单人")
    @ColumnWidth(18)
    @Schema(description = "制单人")
    private String createBy;

    /**
     * 状态
     */
    @ExcelProperty("状态")
    @ColumnWidth(18)
    @Schema(description = "状态")
    private String state;

}