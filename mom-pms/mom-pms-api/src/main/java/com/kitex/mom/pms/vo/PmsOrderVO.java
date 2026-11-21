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

    /** 当前工厂所选采购订单的原单主键，用于核对预览回执。 */
    @Schema(description = "采购订单主键")
    private Long id;

    /** 该采购订单的租户身份，与工厂共同限定收料原件。 */
    @Schema(description = "采购订单租户")
    private Long tenantId;

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
