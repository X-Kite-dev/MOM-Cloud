package com.kitex.mom.mes.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 线边库出入库流水
 *
 * @author kitex
 * @date 2025-02-20 17:26:14
 */
@Data
@Accessors(chain = true)
@Schema(description = "线边库出入库流水")
public class MesWarehouseInoutExcelVO {

    /**
     * 序号
     */
    @ColumnWidth(8)
    @ExcelProperty("序号")
    @Schema(description = "序号")
    private Integer rowNo;

    /**
     * 单据号
     */
    @ColumnWidth(18)
    @ExcelProperty("单据号")
    @Schema(description = "单据号")
    private String billNo;

    /**
     * 来源单号
     */
    @ColumnWidth(18)
    @ExcelProperty("来源单号")
    @Schema(description = "来源单号")
    private String resourceNo;

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
     * 物料条码
     */
    @ColumnWidth(18)
    @ExcelProperty("物料条码")
    @Schema(description = "物料条码")
    private String matBarcode;

    /**
     * 物料条码图片
     */
    @ColumnWidth(18)
    @ExcelProperty("物料条码图片")
    @Schema(description = "物料条码图片")
    private String matBarcodeImage;

//	/**
//	* 容器名称
//	*/
//	@ColumnWidth(18)
//	@ExcelProperty("物料编码")
//    @Schema(description="容器名称")
//    private String containerName;

    /**
     * 操作类型
     */
    @ColumnWidth(18)
    @ExcelProperty("操作类型")
    @Schema(description = "操作类型")
    private String operateType;

    /**
     * 质检状态
     */
    @ColumnWidth(18)
    @ExcelProperty("质检状态")
    @Schema(description = "质检状态")
    private String inspectionStatus;

    /**
     * 物料明细类型 原材料、半成品、在制品
     */
    @ColumnWidth(18)
    @ExcelProperty("物料明细类型")
    @Schema(description = "物料明细类型 原材料、半成品、在制品")
    private String matDetailType;

}