package com.kitex.mom.wms.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 入库单导出类
 *
 * @author kitex
 * @date 2025-1-9 16:11:51
 */
@Data
@Schema(description = "入库单导出类")
public class WmsInBillExcelVO {

    /**
     * 序号
     */
    @ColumnWidth(8)
    @ExcelProperty("序号")
    @Schema(description = "序号")
    private Integer rowNo;

    /**
     * 入库单号
     */
    @ColumnWidth(18)
    @ExcelProperty("入库单号")
    @Schema(description = "入库单号")
    private String billNo;

    /**
     * 入库通知单号
     */
    @ColumnWidth(18)
    @ExcelProperty("入库通知单号")
    @Schema(description = "入库通知单号")
    private Long noticeId;

    /**
     * 完成条数
     */
    @ColumnWidth(12)
    @ExcelProperty("完成条数")
    @Schema(description = "完成条数")
    private Double detailFinishNum;

    /**
     * 未完成条数
     */
    @ColumnWidth(15)
    @ExcelProperty("未完成条数")
    @Schema(description = "未完成条数")
    private Double detailNotFinishNum;

    /**
     * 入库时间
     */
    @ColumnWidth(18)
    @ExcelProperty("入库时间")
    @Schema(description = "入库时间")
    private LocalDateTime inTime;

    /**
     * 备注
     */
    @ColumnWidth(10)
    @ExcelProperty("备注")
    @Schema(description = "备注")
    private String remark;

    /**
     * 审核人
     */
    @ColumnWidth(10)
    @ExcelProperty("审核人")
    @Schema(description = "审核人")
    private String reviewer;

    /**
     * 审核时间
     */
    @ColumnWidth(18)
    @ExcelProperty("审核时间")
    @Schema(description = "审核时间")
    private LocalDateTime reviewTime;

    /**
     * 状态
     */
    @ColumnWidth(8)
    @ExcelProperty("状态")
    @Schema(description = "状态")
    private String state;

}