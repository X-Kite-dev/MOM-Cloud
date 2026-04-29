package com.kitex.mom.wms.vo;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.kitex.common.excel.annotation.ExcelLine;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 库存期初导入
 **/
@Data
@Schema(description = "库存期初导入")
public class WmsLiveStockImportExcelVO implements Serializable {

    private static final long serialVersionUID = 3292352559328102531L;

    @ExcelIgnore
    @ExcelLine
    private Long lineNum;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    @ExcelProperty(value = "物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    @ExcelProperty(value = "物料名称")
    private String matName;

    /**
     * 物料分组编码
     */
    @Schema(description = "物料分组编码")
    @ExcelProperty(value = "物料分组编码")
    private String matGroup;

    /**
     * 物料分组名称
     */
    @Schema(description = "物料分组名称")
    @ExcelProperty(value = "物料分组名称")
    private String matGroupName;

    /**
     * 物料批次
     */
    @Schema(description = "物料批次")
    @ExcelProperty(value = "物料批次")
    private String matBatch;

    /**
     * 规格型号
     */
    @ExcelProperty(value = "规格型号")
    @Schema(description = "规格型号")
    private String matSpec;

    /**
     * 数量
     */
    @ExcelProperty(value = "数量")
    @Schema(description = "数量")
    private Double matNum;

    /**
     * 单位
     */
    @ExcelProperty(value = "单位")
    @Schema(description = "单位")
    private String matUnit;

    /**
     * 生产日期(YYYY-MM-DD)
     */
    @ExcelProperty(value = "生产日期(YYYY-MM-DD)")
    @Schema(description = "生产日期(YYYY-MM-DD)")
    private LocalDate matProduceDate;

    /**
     * 有效期(天)
     */
    @ExcelProperty(value = "有效期(天)")
    @Schema(description = "有效期(天)")
    private Integer matValidPeriod;

    /**
     * 供应商编码
     */
    @ExcelProperty(value = "供应商编码")
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @ExcelProperty(value = "供应商名称")
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 库位编码
     */
    @ExcelProperty(value = "库位编码")
    @Schema(description = "库位编码")
    private String siteCode;

    /**
     * 库存名称
     */
    @ExcelProperty(value = "库存名称")
    @Schema(description = "库存名称")
    private String siteName;

    /**
     * 容器编码
     */
    @ExcelProperty(value = "容器编码")
    @Schema(description = "容器编码")
    private String containerCode;

    /**
     * 入库时间(YYYY-MM-DD HH:mm:ss)
     */
    @ExcelProperty(value = "入库时间(YYYY-MM-DD HH:mm:ss)")
    @Schema(description = "入库时间(YYYY-MM-DD HH:mm:ss)")
    private LocalDateTime inTime;

    /**
     * 包装规格
     */
    @ExcelProperty(value = "包装规格")
    @Schema(description = "包装规格")
    private String matPackSpec;

    /**
     * 仓库编码
     */
    @ExcelProperty(value = "仓库编码")
    @Schema(description = "仓库编码")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @ExcelProperty(value = "仓库名称")
    @Schema(description = "仓库名称")
    private String warehouseName;

    /**
     * 质检结果
     */
    @ExcelProperty(value = "质检结果")
    @Schema(description = "质检结果")
    private String qualityResult;

    /**
     * 是否锁定(0否1是)
     */
    @ExcelProperty(value = "是否锁定(0否1是)")
    @Schema(description = "是否锁定(0否1是)")
    private Integer status;

}
