package com.kitex.mom.pms.dto.app;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 采购收料单
 *
 * @author kitex
 * @date 2025-01-22 11:34:35
 */
@Data
@Schema(description = "采购收料单")
public class WmsPurchaseReceiptParamsDTO {

//	/**
//	 * 采购收料单id
//	 */
//	@Schema(description = "采购收料单id")
//	private Long billId;
    /**
     * 采购收料单号
     */
    @Schema(description = "采购收料单号")
    private String billNo;
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
    @Schema(description = "物料批次")
    private String matBatch;
    /**
     * 生产日期
     */
    @NotBlank(message = "生产日期不能为空")
    @Schema(description = "生产日期")
    private LocalDate matProduceDate;
    /**
     * 采购单价
     */
    @Schema(description = "采购单价")
    @NotNull(message = "采购单价不能为空")
    private BigDecimal price;
    /**
     * 物料数量
     */
    @NotNull(message = "物料数量不能为空")
    @Schema(description = "物料数量")
    private Double matNum;
}