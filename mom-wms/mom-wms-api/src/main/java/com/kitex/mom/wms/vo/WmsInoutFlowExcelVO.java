package com.kitex.mom.wms.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 出入库流水导出
 *
 * @author kitex
 * @date 2024-12-18 18:48:20
 */
@Data
@Schema(description = "出入库流水导出")
public class WmsInoutFlowExcelVO {

    /**
     * 序号
     */
    @ColumnWidth(8)
    @ExcelProperty("序号")
    @Schema(description = "序号")
    private Integer rowNo;

    /**
     * 出入库单号
     */
    @ColumnWidth(18)
    @ExcelProperty("出入库单号")
    @Schema(description = "出入库单号")
    private String billNo;

    /**
     * 业务类型
     */
    @ColumnWidth(15)
    @ExcelProperty("业务类型")
    @Schema(description = "业务类型")
    private String billType;

    /**
     * 操作类型;in入库、out出库
     */
    @ColumnWidth(12)
    @ExcelProperty("操作类型")
    @Schema(description = "操作类型;in入库、out出库")
    private String operateType;

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
     * 物料批次
     */
    @ColumnWidth(15)
    @ExcelProperty("物料批次")
    @Schema(description = "物料批次")
    private String matBatch;

    /**
     * 容器编码
     */
    @ColumnWidth(15)
    @ExcelProperty("容器编码")
    @Schema(description = "容器编码")
    private String containerCode;

    /**
     * 规格型号
     */
    @ColumnWidth(12)
    @ExcelProperty("规格型号")
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 物料数量
     */
    @ColumnWidth(12)
    @ExcelProperty("物料数量")
    @Schema(description = "物料数量")
    private String matNum;

    /**
     * 包装规格
     */
    @ColumnWidth(12)
    @ExcelProperty("包装规格")
    @Schema(description = "包装规格")
    private String matPackSpec;

    /**
     * 物料条码
     */
    @ColumnWidth(15)
    @ExcelProperty("物料条码")
    @Schema(description = "物料条码")
    private String matBarcode;

    /**
     * 供应商编码
     */
    @ColumnWidth(15)
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
     * 库位编号
     */
    @ColumnWidth(15)
    @ExcelProperty("库位编号")
    @Schema(description = "库位编号")
    private String siteCode;

    /**
     * 库位名称
     */
    @ColumnWidth(15)
    @ExcelProperty("库位名称")
    @Schema(description = "库位名称")
    private String siteName;

    /**
     * 生产日期
     */
    @ColumnWidth(15)
    @ExcelProperty("生产日期")
    @Schema(description = "生产日期")
    private LocalDate matProduceDate;

    /**
     * 有效期(天)
     */
    @ColumnWidth(15)
    @ExcelProperty("有效期(天)")
    @Schema(description = "有效期(天)")
    private Integer matValidPeriod;

    /**
     * 有效截止日期
     */
    @ColumnWidth(18)
    @ExcelProperty("有效截止日期")
    @Schema(description = "有效截止日期")
    private LocalDate matValidEndDate;

    /**
     * 车间名称
     */
    @ColumnWidth(12)
    @ExcelProperty("车间名称")
    @Schema(description = "车间名称")
    private String workshop;

    /**
     * 出入库时间
     */
    @ColumnWidth(18)
    @ExcelProperty("出入库时间")
    @Schema(description = "出入库时间")
    private LocalDateTime createTime;

    /**
     * 创建人
     */
    @ColumnWidth(12)
    @ExcelProperty("创建人")
    @Schema(description = "创建人")
    private String createBy;

}