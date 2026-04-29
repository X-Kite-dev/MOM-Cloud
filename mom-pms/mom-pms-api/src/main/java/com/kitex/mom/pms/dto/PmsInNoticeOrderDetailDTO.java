package com.kitex.mom.pms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 采购通知单详情接收DTO
 *
 * @author kitex
 * @date 2025-01-08 15:42:13
 */
@Data
@Schema(description = "采购通知单详情接收DTO")
public class PmsInNoticeOrderDetailDTO {

    /**
     * 采购订单详情id
     */
    @Schema(description = "采购订单详情id")
    private Long documentId;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 数量
     */
    @Schema(description = "数量")
    private Double inNum;

}