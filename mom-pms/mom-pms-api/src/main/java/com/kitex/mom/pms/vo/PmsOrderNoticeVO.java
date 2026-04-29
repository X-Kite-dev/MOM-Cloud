package com.kitex.mom.pms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 采购订单明细VO
 *
 * @author kitex
 * @date 2025-01-07 15:57:55
 */
@Data
@Schema(description = "采购订单明细VO")
public class PmsOrderNoticeVO {

    /**
     * 采购订单明细ID
     */
    @Schema(description = "采购订单明细ID")
    private Long orderDetailId;

    /**
     * 物料编码
     */
    @Schema(description = "物料编码")
    private String matCode;
    /**
     * 物料名称
     */
    @Schema(description = "物料名称")
    private String matName;
    /**
     * 物料单位
     */
    @Schema(description = "物料单位")
    private String matUnit;
    /**
     * 物料规格
     */
    @Schema(description = "物料规格")
    private String matSpec;
    /**
     * 已到货数量
     */
    @Schema(description = "已到货数量")
    private Double arrivalNum;
    /**
     * 采购数量
     */
    @Schema(description = "采购数量")
    private Double orderNum;
}