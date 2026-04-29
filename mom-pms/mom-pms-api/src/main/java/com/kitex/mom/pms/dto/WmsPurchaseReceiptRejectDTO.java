package com.kitex.mom.pms.dto;

import com.kitex.mom.pms.entity.WmsPurchaseReceiptBatchDetailEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 采购入库通知单作废DTO
 *
 * @author kitex
 * @date 2025-04-01 11:21:48
 */
@Data
@Schema(description = "采购入库通知单作废DTO")
public class WmsPurchaseReceiptRejectDTO {

    /**
     * 采购收料单id
     */
    @Schema(description = "采购收料单id")
    private Long documentId;

    /**
     * 判定类型
     */
    @Schema(description = "判定类型")
    private String genType;

    /**
     * 采购收料单收料明细列表
     */
    @Schema(description = "采购收料单收料明细列表")
    private List<WmsPurchaseReceiptBatchDetailEntity> result;
}