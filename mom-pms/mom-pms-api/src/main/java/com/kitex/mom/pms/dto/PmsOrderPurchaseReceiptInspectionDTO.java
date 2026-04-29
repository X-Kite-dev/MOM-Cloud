package com.kitex.mom.pms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 采购收料单质检DTO
 *
 * @author kitex
 * @date 2025-02-21 09:23:06
 */
@Data
@Schema(description = "采购收料单质检DTO")
public class PmsOrderPurchaseReceiptInspectionDTO {
    /**
     * 采购订单号
     */
    @Schema(description = "采购订单号")
    private String pmsOrderNo;

    /**
     * 供应商编码
     */
    @Schema(description = "供应商编码")
    private String supplierCode;

    /**
     * 供应商名称
     */
    @Schema(description = "供应商名称")
    private String supplierName;

    /**
     * 是否推送质检 false 不推送 true 推送
     */
    @Schema(description = "是否推送质检 false 不推送 true 推送")
    private Boolean qms;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;

    /**
     * 采购收料单质检详情DTO
     */
    @Schema(description = "采购收料单质检详情DTO")
    private List<PmsOrderPurchaseReceiptInspectionDetailDTO> details;

    /**
     * 需要质检的采购收料单质检详情DTO
     */
    @Schema(description = "需要质检的采购收料单质检详情DTO")
    private List<PmsOrderPurchaseReceiptInspectionDetailDTO> qmsDetails;

    /**
     * 需要入库的采购收料单质检详情DTO
     */
    @Schema(description = "需要入库的采购收料单质检详情DTO")
    private List<PmsOrderPurchaseReceiptInspectionDetailDTO> wmsDetails;
}