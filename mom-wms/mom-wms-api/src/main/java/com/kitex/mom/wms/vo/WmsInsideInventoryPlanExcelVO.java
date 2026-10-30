package com.kitex.mom.wms.vo;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import lombok.Data;

import java.io.Serializable;

/**
 * 仓库盘点计划 excel实体
 *
 * @author kitex
 * @date 2025-01-09 17:31:51
 */
@Data
@ColumnWidth(30)
public class WmsInsideInventoryPlanExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入时候回显行号
     */
    @ExcelLine
    @ExcelIgnore
    private Long lineNum;

    /**
     * 盘点编号
     */
    @ExcelProperty("盘点编号")
    private String planNo;

    /**
     * 仓库编号
     */
    @ExcelProperty("仓库编号")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @ExcelProperty("仓库名称")
    private String warehouseName;

    @ExcelProperty("盘点方式")
    private String stockFreezeMode;

    /**
     * 状态：0 新建/ 1正在执行中/2 已结束
     */
    @ExcelProperty("状态：0正在执行中/1 已结束")
    private String status;

    /**
     * 盘点损益处理状态：0 待处理/ 1已处理
     */
    @ExcelProperty("盘点损益处理状态：0 待处理/ 1已处理")
    private String handleState;
}
