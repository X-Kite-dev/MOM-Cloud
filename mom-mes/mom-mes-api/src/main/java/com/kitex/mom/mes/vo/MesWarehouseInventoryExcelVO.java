package com.kitex.mom.mes.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 仓库盘点计划 excel实体
 *
 * @author kitex
 * @date 2025-2-26 17:45:36
 */
@Data
@ColumnWidth(18)
public class MesWarehouseInventoryExcelVO {

    /**
     * 序号
     */
    @ColumnWidth(8)
    @ExcelProperty("序号")
    @Schema(description = "序号")
    private Integer rowNo;

    /**
     * 盘点编号
     */
    @ExcelProperty("盘点编号")
    private String planNo;

    /**
     * 状态：0 执行中 / 1 已完成
     */
    @ExcelProperty("状态")
    private String status;

    /**
     * 盘点损益处理状态：0 待处理/ 1已处理
     */
    @ExcelProperty("损益处理状态")
    private String handleState;
}