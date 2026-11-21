package com.kitex.mom.pms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

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
    /** 原采购行订购量，按原单物料单位计。 */
    @Schema(description = "原订购量")
    private Double originalOrderQuantity;

    /** 已受控撤回的数量，按原采购单位计。 */
    @Schema(description = "已撤回量")
    private Double cancelledNum;

    /** 未结收料单中尚未过账的数量，按原采购单位计。 */
    @Schema(description = "其他收料单占用量")
    private BigDecimal occupiedNum;

    /** 可用于本次新增收料的数量，已扣除到货、撤回与未过账占用。 */
    @Schema(description = "本次可收量")
    private Double orderNum;

    /** 原采购行约定的到货日期，用于区分同料不同交期的行。 */
    @Schema(description = "约定到货日期")
    private LocalDate deliveryTime;
}
