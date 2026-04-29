package com.kitex.mom.wms.vo;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.kitex.common.excel.annotation.ExcelLine;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 库内移动 excel实体
 *
 * @author kitex
 * @date 2025-01-09 17:41:37
 */
@Data
@ColumnWidth(30)
public class WmsMaterialMoveSiteExcelVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入时候回显行号
     */
    @ExcelLine
    @ExcelIgnore
    private Long lineNum;

    /**
     * 库内移动号
     */
    @ExcelProperty("库内移动号")
    private String billNo;

    /**
     * 物料编码
     */
    @ExcelProperty("物料编码")
    private String matCode;

    /**
     * 物料名称
     */
    @ExcelProperty("物料名称")
    private String matName;

    /**
     * 物料分组
     */
    @ExcelProperty("物料分组")
    private String matGroup;

    /**
     * 物料分组名称
     */
    @ExcelProperty("物料分组名称")
    private String matGroupName;

    /**
     * 物料条码
     */
    @ExcelProperty("物料条码")
    private String matBarcode;

    /**
     * 物料批次
     */
    @ExcelProperty("物料批次")
    private String matBatch;

    /**
     * 包装规格
     */
    @ExcelProperty("包装规格")
    private String matPackSpec;

    /**
     * 供应商
     */
    @ExcelProperty("供应商")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @ExcelProperty("供应商名称")
    private String supplierName;

    /**
     * 仓库编码
     */
    @ExcelProperty("仓库编码")
    private String warehouseCode;

    /**
     * 仓库名称
     */
    @ExcelProperty("仓库名称")
    private String warehouseName;

    /**
     * 新库位编码
     */
    @ExcelProperty("新库位编码")
    private String newSiteCode;

    /**
     * 新库位名称
     */
    @ExcelProperty("新库位名称")
    private String newSiteName;

    /**
     * 旧库位编码
     */
    @ExcelProperty("旧库位编码")
    private String oldSiteCode;

    /**
     * 旧库位名称
     */
    @ExcelProperty("旧库位名称")
    private String oldSiteName;

    /**
     * 容器编码
     */
    @ExcelProperty("容器编码")
    private String containerCode;

    /**
     * 入库时间
     */
    @ExcelProperty("入库时间")
    private LocalDateTime inTime;

    /**
     * 数量
     */
    @ExcelProperty("数量")
    private Double matNum;

    /**
     * 单位
     */
    @ExcelProperty("单位")
    private String matUnit;

    /**
     * 生产日期
     */
    @ExcelProperty("生产日期")
    private LocalDate matProduceDate;

    /**
     * 规格型号
     */
    @ExcelProperty("规格型号")
    private String matSpec;

    /**
     * 有效期
     */
    @ExcelProperty("有效期")
    private Long matValidPeriod;

    /**
     * 有效截至日期
     */
    @ExcelProperty("有效截至日期")
    private LocalDate matValidEndDate;

    /**
     * 包装单位
     */
    @ExcelProperty("包装单位")
    private String operateUnit;

    /**
     * 包装单位数量
     */
    @ExcelProperty("包装单位数量")
    private Double formatNum;
}