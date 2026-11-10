package com.kitex.mom.pms.dto.app;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 已存在采购收料单的单行实收登记，数量单位沿原收料行基本单位。
 * 不负责创建收料单、修改价格、判定质量或办理WMS入库。
 *
 * @author kitex
 * @date 2025-01-22 11:34:35
 */
@Data
@Schema(description = "采购收料单")
public class WmsPurchaseReceiptParamsDTO {

	/**
	 * 采购收料单号
	 */
	@Schema(description = "采购收料单号")
	@NotBlank(message = "采购收料单号不能为空")
	private String billNo;

	/**
	 * 原收料明细主键；同物料多行时必须指定，仍须归属当前租户、工厂及收料单。
	 * 旧客户端可空，但仅允许按物料唯一匹配一行。
	 */
	@Schema(description = "原收料明细主键，同物料多行时必填")
	private Long receiptDetailId;
	/**
	 * 物料编码
	 */
	@NotBlank(message = "物料编码不能为空")
	@Schema(description = "物料编码")
	private String matCode;
	/**
	 * 物料批次
	 */
	@NotBlank(message = "物料批次不能为空")
	@Size(max = 255, message = "物料批次最多255个字符")
	@Schema(description = "物料批次")
	private String matBatch;
	/**
	 * 生产日期
	 */
	@NotNull(message = "生产日期不能为空")
	@PastOrPresent(message = "生产日期不能晚于今天")
	@Schema(description = "生产日期")
	private LocalDate matProduceDate;
	/**
	 * 旧客户端兼容字段；登记端忽略此值，沿原收料行或所属采购订单行保持权威价格。
	 */
	@Schema(description = "兼容旧请求，登记端不接受客户端改价", accessMode = Schema.AccessMode.READ_ONLY)
	private BigDecimal price;
	/**
	 * 本次实收数量，原收料行基本单位；正数，最多18位整数、6位小数，不超过应收剩余量。
	 */
	@NotNull(message = "物料数量不能为空")
	@DecimalMin(value = "0", inclusive = false, message = "本次实收数量必须大于0")
	@Digits(integer = 18, fraction = 6, message = "实收数量最多18位整数和6位小数")
	@Schema(description = "物料数量")
	private BigDecimal matNum;
}
