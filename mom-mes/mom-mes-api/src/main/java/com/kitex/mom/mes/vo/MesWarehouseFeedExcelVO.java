package com.kitex.mom.mes.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 线边库投料记录
 *
 * @author kitex
 * @date 2025-02-20 17:22:19
 */
@Data
@Schema(description = "线边库投料记录")
public class MesWarehouseFeedExcelVO {

    /**
     * 序号
     */
    @ColumnWidth(8)
    @ExcelProperty("序号")
    @Schema(description = "序号")
    private Integer rowNo;

    /**
     * 任务编码
     */
    @ColumnWidth(18)
    @ExcelProperty("任务编码")
    @Schema(description = "任务编码")
    private String arrangeCode;

    /**
     * 工序任务编码
     */
    @ColumnWidth(18)
    @ExcelProperty("工序任务编码")
    @Schema(description = "工序任务编码")
    private String produceTaskCode;

    /**
     * 生产工单编码
     */
    @ColumnWidth(18)
    @ExcelProperty("生产工单编码")
    @Schema(description = "生产工单编码")
    private String taskCode;

    /**
     * 物料编码
     */
    @ColumnWidth(18)
    @ExcelProperty("物料编码")
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @ColumnWidth(18)
    @ExcelProperty("物料名称")
    @Schema(description = "物料名称")
    private String matName;

    /**
     * 规格型号
     */
    @ColumnWidth(18)
    @ExcelProperty("规格型号")
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 批次号
     */
    @ColumnWidth(18)
    @ExcelProperty("批次号")
    @Schema(description = "批次号")
    private String matBatch;

    /**
     * 数量
     */
    @ColumnWidth(18)
    @ExcelProperty("数量")
    @Schema(description = "数量")
    private BigDecimal operateNum;

    /**
     * 单位
     */
    @ColumnWidth(18)
    @ExcelProperty("单位")
    @Schema(description = "单位")
    private String matUnit;

    /**
     * 生产日期
     */
    @ColumnWidth(18)
    @ExcelProperty("生产日期")
    @Schema(description = "生产日期")
    private LocalDate matProduceDate;

    /**
     * 有效期
     */
    @ColumnWidth(18)
    @ExcelProperty("有效期")
    @Schema(description = "有效期")
    private Integer matValidPeriod;

    /**
     * 有效截止日期
     */
    @ColumnWidth(18)
    @ExcelProperty("有效截止日期")
    @Schema(description = "有效截止日期")
    private LocalDate matValidEndDate;

    /**
     * 供应商编码
     */
    @ColumnWidth(18)
    @ExcelProperty("供应商编码")
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @ColumnWidth(18)
    @ExcelProperty("供应商名称")
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 容器编码
     */
    @ColumnWidth(18)
    @ExcelProperty("容器编码")
    @Schema(description = "容器编码")
    private String containerCode;

    /**
     * 库位编码
     */
    @ColumnWidth(18)
    @ExcelProperty("库位编码")
    @Schema(description = "库位编码")
    private String siteCode;

    /**
     * 库位名称
     */
    @ColumnWidth(18)
    @ExcelProperty("库位名称")
    @Schema(description = "库位名称")
    private String siteName;

    /**
     * 投料时间
     */
    @ColumnWidth(18)
    @ExcelProperty("投料时间")
    @Schema(description = "投料时间")
    private LocalDateTime feedTime;

    /**
     * 投料人员
     */
    @ColumnWidth(18)
    @ExcelProperty("投料人员")
    @Schema(description = "投料人员")
    private String feedPersonCode;

    /**
     * 投料人员姓名
     */
    @ColumnWidth(18)
    @ExcelProperty("投料人员姓名")
    @Schema(description = "投料人员姓名")
    private String feedPersonName;

}