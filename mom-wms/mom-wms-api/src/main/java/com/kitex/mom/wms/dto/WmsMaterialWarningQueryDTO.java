package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;

/**
 * 物料预警查询DTO
 *
 * @author kitex
 * @date 2024-12-23 17:23:41
 */
@Data
public class WmsMaterialWarningQueryDTO {

	@Schema(description = "预警号")
	private String billNo;

	/**
	 * 物料编码
	 */
	@Schema(description = "物料编码")
	private String matCode;

	/**
	 * 物料名称
	 */
	@Schema(description = "物料名称")
	private String matName;

	/**
	 * 物料批次
	 */
	@Schema(description = "物料批次")
	private String matBatch;

	/**
	 * 仓库编码
	 */
	@Schema(description = "仓库编码")
	private String warehouseCode;

	/**
	 * 仓库名称
	 */
	@Schema(description = "仓库名称")
	private String warehouseName;

	/**
	 * 库位编码
	 */
	@Schema(description = "库位编码")
	private String siteCode;

	/**
	 * 库存名称
	 */
	@Schema(description = "库存名称")
	private String siteName;

	/**
	 * 预警时间
	 */
	@Schema(description = "预警时间")
	private LocalDate[] createTime;

	/**
	 * 预警类型, 0 过期预警 1 呆滞预警
	 */
	@Schema(description = "预警类型, 0 过期预警 1 呆滞预警")
	private String type;
}
