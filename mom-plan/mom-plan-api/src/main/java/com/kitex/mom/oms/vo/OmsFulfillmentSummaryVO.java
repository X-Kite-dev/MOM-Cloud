package com.kitex.mom.oms.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 单一OMS交付行的跨PLAN/MRP/MES/PMS/WMS履约摘要。 */
@Data
@Schema(description = "订单交付行履约摘要")
public class OmsFulfillmentSummaryVO implements Serializable {
    private Long orderDetailId;
    private String orderCode;
    private String factoryCode;
    private String productCode;
    private String demandNo;
    private LocalDate promisedDate;
    private BigDecimal orderedQuantity;
    private BigDecimal producedQuantity;
    private BigDecimal stockedQuantity;
    private BigDecimal deliveredQuantity;
    private BigDecimal remainingQuantity;
    private BigDecimal physicalStockQuantity;
    private BigDecimal qualifiedStockQuantity;
    private BigDecimal qualityHeldStockQuantity;
    private BigDecimal otherWarehouseStockQuantity;
    private BigDecimal lockedOrExpiredStockQuantity;
    private BigDecimal reservedStockQuantity;
    private BigDecimal productionReservedStockQuantity;
    private BigDecimal salesReservedStockQuantity;
    private BigDecimal currentOrderReservedStockQuantity;
    private BigDecimal priorCommittedQuantity;
    private BigDecimal netDeliverableQuantity;
    private BigDecimal deliverableShortageQuantity;
    private String deliverabilityUnit;
    private String deliverabilityPolicy;
    private String deliverabilityMessage;
    private LocalDateTime deliverabilityCalculatedTime;
    private BigDecimal fulfillmentRate;
    private Integer mrpLineCount;
    private Integer planCount;
    private Integer mesTaskCount;
    private Integer purchasePlanCount;
    private Integer deliverPlanCount;
    /** NOT_STARTED、PLANNING、SUPPLYING、PRODUCING、READY_TO_DELIVER、DELIVERING、COMPLETED、BLOCKED。 */
    private String overallStatus;
    /** NORMAL、WARNING、CRITICAL、UNKNOWN。 */
    private String riskLevel;
    private String planDataStatus;
    private String mesDataStatus;
    private String pmsDataStatus;
    private String wmsDataStatus;
    /** AVAILABLE、NOT_STARTED、UNAVAILABLE、INCONSISTENT。 */
    private String qualityDeliverabilityStatus;
    private List<String> riskMessages;
    private LocalDateTime calculatedTime;
}
