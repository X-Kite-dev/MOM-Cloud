package com.kitex.mom.oms.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 订单评审表
 *
 * @author kitex
 * @date 2025-01-07 14:32:02
 */
@Data
@ColumnWidth(20)
public class OmsOrderBaseReviewExportVo {


    /**
     * 订单号
     */
    @ExcelProperty(value = "订单号")
    @Schema(description = "订单号")
    private String orderCode;

    /**
     * 产品编码
     */
    @ExcelProperty(value = "产品编码")
    @Schema(description = "产品编码")
    private String productCode;

    /**
     * 产品名称
     */
    @ExcelProperty(value = "产品名称")
    @Schema(description = "产品名称")
    private String productName;

    /**
     * 规格型号
     */
    @ExcelProperty(value = "规格型号")
    @Schema(description = "规格型号")
    private String productSpec;

    /**
     * 客户编码
     */
    @ExcelProperty(value = "客户编码")
    @Schema(description = "客户编码")
    private String customerCode;

    /**
     * 客户名称
     */
    @ExcelProperty(value = "客户名称")
    @Schema(description = "客户名称")
    private String customerName;

    /**
     * 客户零件号
     */
    @ExcelProperty(value = "客户零件号")
    @Schema(description = "客户零件号")
    private String customerPartCode;

    /**
     * 订单类型
     */
    @ExcelProperty(value = "订单类型")
    @Schema(description = "订单类型")
    private String orderType;

    /**
     * 订单状态 1 待评审 2评审完成
     */
    @ExcelProperty(value = "订单状态")
    @Schema(description = "订单状态 1 待评审 2评审完成")
    private String orderState;

    /**
     * 是否变更订单 0 否 1是
     */
    @ExcelProperty(value = "变更订单")
    @Schema(description = "是否变更订单 0 否 1是")
    private String changeType;

}