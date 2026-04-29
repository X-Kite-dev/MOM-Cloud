package com.kitex.mom.wms.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 出库通知单明细导出类
 *
 * @author kitex
 * @date 2025-1-10 09:07:08
 */
@Data
@Schema(description = "出库通知单明细导出类")
public class WmsOutNoticeDetailExcelVO {

    /**
     * 序号
     */
    @ColumnWidth(8)
    @ExcelProperty("序号")
    @Schema(description = "序号")
    private Integer rowNo;

    /**
     * 出库通知单号
     */
    @ColumnWidth(20)
    @ExcelProperty("出库通知单号")
    @Schema(description = "出库通知单号")
    private Long noticeId;

    /**
     * 物料编码
     */
    @ColumnWidth(15)
    @ExcelProperty("物料编码")
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @ColumnWidth(15)
    @ExcelProperty("物料名称")
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 规格型号
     */
    @ColumnWidth(15)
    @ExcelProperty("规格型号")
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 创建时间
     */
    @ColumnWidth(18)
    @ExcelProperty("创建时间")
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /**
     * 创建人
     */
    @ColumnWidth(10)
    @ExcelProperty("创建人")
    @Schema(description = "创建人")
    private String createBy;

    /**
     * 通知出库数
     */
    @ColumnWidth(15)
    @ExcelProperty("通知出库数")
    @Schema(description = "通知出库数")
    private String noticeOutNum;

    /**
     * 已出库数
     */
    @ColumnWidth(15)
    @ExcelProperty("已出库数")
    @Schema(description = "已出库数")
    private String outNum;

    /**
     * 未出库数
     */
    @ColumnWidth(15)
    @ExcelProperty("未出库数")
    @Schema(description = "未出库数")
    private String notOutNum;

    /**
     * 状态
     */
    @ColumnWidth(18)
    @ExcelProperty("状态")
    @Schema(description = "状态")
    private String state;

}