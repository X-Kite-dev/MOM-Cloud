package com.kitex.mom.wms.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 出库通知单导出类
 *
 * @author kitex
 * @date 2025-1-10 09:04:37
 */
@Data
@Schema(description = "出库通知单导出类")
public class WmsOutNoticeExcelVO {

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
    private Long id;

    /**
     * 出库完成时间
     */
    @ColumnWidth(18)
    @ExcelProperty("出库完成时间")
    @Schema(description = "出库完成时间")
    private LocalDateTime completeTime;

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
    private Double noticeOutNum;

    /**
     * 出库总数
     */
    @ColumnWidth(12)
    @ExcelProperty("出库数")
    @Schema(description = "出库数")
    private Double allOutNum;

    /**
     * 未出库总数
     */
    @ColumnWidth(15)
    @ExcelProperty("未出库数")
    @Schema(description = "未出库数")
    private Double allNotOutNum;

    /**
     * 状态
     */
    @ColumnWidth(8)
    @ExcelProperty("状态")
    @Schema(description = "状态")
    private String state;

    /**
     * 备注
     */
    @ColumnWidth(18)
    @ExcelProperty("备注")
    @Schema(description = "备注")
    private String remark;


}