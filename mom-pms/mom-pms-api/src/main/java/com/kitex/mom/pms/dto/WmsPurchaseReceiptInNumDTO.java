package com.kitex.mom.pms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 采购收料入库数量DTO
 *
 * @author kitex
 * @date 2025-04-01 11:07:40
 */
@Data
@Schema(description = "采购收料入库数量DTO")
public class WmsPurchaseReceiptInNumDTO {

    @Schema(description = "采购订单明细ID")
    private Long orderDetailId;

    /**
     * 采购收料单id
     */
    @Schema(description = "采购收料单id")
    private Long documentId;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 物料批次
     */
    @Schema(description = "物料批次")
    private String matBatch;

    /**
     * 入库数量
     */
    @Schema(description = "入库数量")
    private Double matNum;
}
