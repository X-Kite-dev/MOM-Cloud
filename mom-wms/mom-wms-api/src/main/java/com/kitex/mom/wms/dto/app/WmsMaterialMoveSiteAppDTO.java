package com.kitex.mom.wms.dto.app;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 库内移动DTO
 *
 * @author kitex
 * @date 2024-12-30 14:13:22
 */
@Data
@Schema(description = "库内移动")
public class WmsMaterialMoveSiteAppDTO {

	@Schema(description = "移动端移库永久幂等请求号；网络超时后重试须保持不变")
	@NotBlank(message = "移库请求唯一号不能为空")
	private String requestId;

	@Schema(description = "扫码选中的精确来源库存行ID；单件序列号移库必填")
	private Long sourceStockId;

	@Schema(description = "现场扫描的原始实物条码；单件序列号移库必填")
	private String matBarcode;

	@Schema(description = "原移动端移库单号；用于记录和核账，不代表永久幂等请求号")
	private String billNo;

	/**
	 * 物料编码
	 */
	@Schema(description = "物料编码")
	@NotNull(message = "物料不能为空")
	private String matCode;

	/**
	 * 物料名称
	 */
	@Schema(description = "物料名称")
	private String matName;

	/**
	 * 物料分组
	 */
	@Schema(description = "物料分组")
	private String matGroup;

	/**
	 * 物料分组名称
	 */
	@Schema(description = "物料分组名称")
	private String matGroupName;

	/**
	 * 物料批次
	 */
	@Schema(description = "物料批次")
	@NotNull(message = "物料批次不能为空")
	private String matBatch;

	/**
	 * 供应商
	 */
	@Schema(description = "供应商")
	@NotNull(message = "供应商不能为空")
	private String supplierCode;

	/**
	 * 供应商名称
	 */
	@Schema(description = "供应商名称")
	private String supplierName;

	/**
	 * 仓库编码
	 */
	@Schema(description = "仓库编码")
	@NotNull(message = "仓库编码不能为空")
	private String warehouseCode;

	/**
	 * 仓库名称
	 */
	@Schema(description = "仓库名称")
	private String warehouseName;

	/**
	 * 新库位编码
	 */
	@Schema(description = "新库位编码")
	@NotNull(message = "新库位不能为空")
	private String newSiteCode;

	/**
	 * 新库存名称
	 */
	@Schema(description = "新库位名称")
	private String newSiteName;

	/**
	 * 旧库位编码
	 */
	@Schema(description = "旧库位编码")
	@NotNull(message = "旧库位不能为空")
	private String oldSiteCode;

	/**
	 * 旧库存名称
	 */
	@Schema(description = "旧库存名称")
	private String oldSiteName;

	/**
	 * 旧容器编码
	 */
	@Schema(description = "旧容器编码")
	private String oldContainerCode;

	/**
	 * 新容器编码
	 */
	@Schema(description = "新容器编码")
	private String newContainerCode;

	/**
	 * 数量
	 */
	@Schema(description = "数量")
	@NotNull(message = "数量不能为空")
	private Double operateNum;

	/**
	 * 单位
	 */
	@Schema(description = "单位")
	private String matUnit;

	/**
	 * 工厂编码 - 数据所属的工厂编码
	 */
	@Schema(description = "工厂编码 - 数据所属的工厂编码")
	private String factoryCode;
}
