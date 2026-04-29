package com.kitex.mom.pms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 采购收料单质检详情DTO
 *
 * @author kitex
 * @date 2025-02-21 09:23:06
 */
@Data
@Schema(description = "采购收料单质检详情DTO")
public class PmsOrderPurchaseReceiptInspectionDetailDTO {
    /**
     * 采购订单明细ID
     */
    @Schema(description = "采购订单明细ID")
    private Long orderDetailId;
    /**
     * 采购订单号
     */
    @Schema(description = "通知单id")
    private String noticeId;
    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;
    /**
     * 物料编码
     */
    @Schema(description = "物料名称")
    private String matName;
    /**
     * 物料批次
     */
    @Schema(description = "物料批次")
    private String matBatch;
    /**
     * 物料单位
     */
    @Schema(description = "物料单位")
    private String matUnit;
    /**
     * 规格型号
     */
    @Schema(description = "规格型号")
    private String matSpec;
    /**
     * 生产日期
     */
    @Schema(description = "生产日期")
    private LocalDate matProduceDate;
    /**
     * 单价
     */
    @Schema(description = "单价")
    private BigDecimal price;
    /**
     * 物料数量
     */
    @Schema(description = "物料数量")
    private Double shouldNum;
}