package com.kitex.mom.pms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 采购订单VO
 *
 * @author kitex
 * @date 2025-01-08 16:29:13
 */
@Data
@Schema(description = "采购订单VO")
public class PmsOrderVO {
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
     * 工厂编码ID
     */
    @Schema(description = "工厂编码ID")
    private String factoryCode;

    /**
     * 采购订单明细VO
     */
    @Schema(description = "采购订单明细VO")
    private List<PmsOrderNoticeVO> details;
}