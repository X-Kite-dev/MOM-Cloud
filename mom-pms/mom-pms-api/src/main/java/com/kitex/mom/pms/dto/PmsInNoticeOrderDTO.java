package com.kitex.mom.pms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 采购通知单接收DTO
 *
 * @author kitex
 * @date 2025-01-08 15:12:37
 */
@Data
@Schema(description = "采购通知单接收DTO")
public class PmsInNoticeOrderDTO {

    /**
     * 采购订单明细ID
     */
    @Schema(description = "采购订单明细ID")
    private Long orderDetailId;

    /**
     * 采购订单号
     */
    @Schema(description = "采购订单号")
    private String pmsOrderNo;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;

    /**
     * 数量
     */
    @Schema(description = "数量")
    private Double num;

}