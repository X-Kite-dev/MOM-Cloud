package com.kitex.mom.wms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * OMS交付行在WMS当前质量、效期、锁定、预留和订单竞争口径下的可交量快照。
 */
@Data
@Accessors(chain = true)
@Schema(description = "OMS交付行质量净可交量快照")
public class WmsOmsDeliverabilitySnapshot {
    private Long orderDetailId;
    private String orderCode;
    private String factoryCode;
    private String productCode;
    /**
     * AVAILABLE / NOT_STARTED / INCONSISTENT。
     */
    private String status;
    private String unitCode;
    private BigDecimal physicalQuantity;
    private BigDecimal qualifiedQuantity;
    private BigDecimal qualityHeldQuantity;
    private BigDecimal otherWarehouseQuantity;
    private BigDecimal lockedQuantity;
    private BigDecimal expiredQuantity;
    private BigDecimal productionReservedQuantity;
    private BigDecimal salesReservedQuantity;
    private BigDecimal currentOrderReservedQuantity;
    private BigDecimal reservedQuantity;
    private BigDecimal qualifiedAvailableQuantity;
    private BigDecimal priorCommittedQuantity;
    private BigDecimal orderRemainingQuantity;
    private BigDecimal netDeliverableQuantity;
    private BigDecimal shortageQuantity;
    private Integer stockLineCount;
    private Integer competingPlanCount;
    private String allocationPolicy;
    private String message;
    private LocalDateTime observedAt;
}
