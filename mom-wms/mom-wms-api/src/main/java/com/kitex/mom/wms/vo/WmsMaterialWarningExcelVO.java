package com.kitex.mom.wms.vo;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import com.kitex.common.excel.annotation.ExcelLine;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 物料预警表 excel实体
 *
 * @author kitex
 * @date 2025-01-09 17:56:05
 */
@Data
public class WmsMaterialWarningExcelVO implements Serializable {

	private static final long serialVersionUID = 1L;

	/**
	 * 导入时候回显行号
	 */
	@ExcelLine
	@ExcelIgnore
	private Long lineNum;

	/**
	 * 预警号
	 */
	@ExcelProperty("预警号")
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
	 * 规格型号
	 */
	@ExcelProperty("规格型号")
	private String matSpec;

	/**
	 * 物料批次
	 */
	@ExcelProperty("物料批次")
	private String matBatch;

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
	 * 库位编码
	 */
	@ExcelProperty("库位编码")
	private String siteCode;

	/**
	 * 库存名称
	 */
	@ExcelProperty("库存名称")
	private String siteName;

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
	 * 预警天数
	 */
	@ExcelProperty("预警天数")
	private Integer warningDays;
	@ExcelProperty("预警类型：0过期，1呆滞")
	private String type;

	@ExcelProperty("采用截止日期或时刻")
	private String warningDeadline;

	@ExcelProperty("触发时超出天数")
	private Long excessDays;

	@ExcelProperty("预警时间")
	private LocalDateTime createTime;
}
