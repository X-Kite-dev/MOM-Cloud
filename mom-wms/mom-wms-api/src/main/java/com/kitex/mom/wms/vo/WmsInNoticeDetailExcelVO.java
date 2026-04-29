package com.kitex.mom.wms.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 入库通知单明细导出类
 *
 * @author kitex
 * @date 2024-12-10 15:11:37
 */
@Data
@Schema(description = "入库通知单明细导出类")
public class WmsInNoticeDetailExcelVO {

    /**
     * 序号
     */
    @ColumnWidth(8)
    @ExcelProperty("序号")
    @Schema(description = "序号")
    private Integer rowNo;

    /**
     * 入库通知单号
     */
    @ColumnWidth(20)
    @ExcelProperty("入库通知单号")
    @Schema(description = "入库通知单号")
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
     * 通知入库数
     */
    @ColumnWidth(15)
    @ExcelProperty("通知入库数")
    @Schema(description = "通知入库数")
    private String noticeInNum;

    /**
     * 已入库数
     */
    @ColumnWidth(15)
    @ExcelProperty("已入库数")
    @Schema(description = "已入库数")
    private String inNum;

    /**
     * 未入库数
     */
    @ColumnWidth(15)
    @ExcelProperty("未入库数")
    @Schema(description = "未入库数")
    private String notInNum;

    /**
     * 状态
     */
    @ColumnWidth(18)
    @ExcelProperty("状态")
    @Schema(description = "状态")
    private String state;

}