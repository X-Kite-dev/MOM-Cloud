package com.kitex.mom.wms.vo;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 调拨单 excel实体
 *
 * @author kitex
 * @date 2025-01-09 17:33:11
 */
@Data
@ColumnWidth(30)
public class WmsAllocationBillExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入时候回显行号
     */
    @ExcelLine
    @ExcelIgnore
    private Long lineNum;

    /**
     * 调拨单号
     */
    @ExcelProperty("调拨单号")
    private String billNo;

    /**
     * 旧仓库编码
     */
    @ExcelProperty("旧仓库编码")
    private String oldWarehouseCode;

    /**
     * 旧仓库名称
     */
    @ExcelProperty("旧仓库名称")
    private String oldWarehouseName;

    /**
     * 新仓库编码
     */
    @ExcelProperty("新仓库编码")
    private String newWarehouseCode;

    /**
     * 新仓库名称
     */
    @ExcelProperty("新仓库名称")
    private String newWarehouseName;

    /**
     * 调拨完成状态, 0未完成 1完成
     */
    @ExcelProperty("调拨完成状态, 0未完成 1完成")
    private String allocationState;

    /**
     * 完成时间
     */
    @ExcelProperty("完成时间")
    private LocalDateTime finishTime;

    /**
     * 完成操作人
     */
    @ExcelProperty("完成操作人")
    private String finishByName;

    /**
     * 备注
     */
    @ExcelProperty("备注")
    private String remark;
}