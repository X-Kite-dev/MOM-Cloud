package com.kitex.mom.wms.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class WmsSalesStockReservationCommand {
    private Long deliverPlanId;
    private String requestId;
    /**
     * 为空时，预留/释放当前尚未覆盖的全部剩余数量。
     */
    private BigDecimal quantity;
    private String reason;
}
