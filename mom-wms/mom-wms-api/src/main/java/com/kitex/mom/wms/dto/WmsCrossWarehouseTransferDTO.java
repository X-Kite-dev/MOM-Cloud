package com.kitex.mom.wms.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 跨仓缺料调拨建议与Agent受控执行契约。
 * 建议只使用WMS权威库存、活动预留、质量/效期状态和在途调拨，不直接修改库存。
 */
public final class WmsCrossWarehouseTransferDTO {
    private WmsCrossWarehouseTransferDTO() {
    }

    @Data
    public static class Query {
        private String factoryCode;
        /**
         * v7及以后为true；历史v6重试保留24小时连续时效语义。
         */
        private Boolean routePolicyRequired;
        private List<Requirement> requirements;
    }

    @Data
    public static class Requirement {
        private String referenceId;
        private String targetWarehouseCode;
        private String materialCode;
        private BigDecimal requiredNum;
        private LocalDate demandDate;
        /**
         * NORMAL/URGENT/EMERGENCY；为空时按需求日期确定性判定。
         */
        private String urgencyLevel;
    }

    @Data
    @Accessors(chain = true)
    public static class Recommendation {
        private String referenceId;
        private String targetWarehouseCode;
        private String targetWarehouseName;
        private String materialCode;
        private BigDecimal requiredNum;
        /**
         * 目标仓当前全部在途数量，用于执行前检测新增/取消在途。
         */
        private BigDecimal targetInTransitNum;
        /**
         * 本批建议按需求顺序实际抵扣的在途数量，避免多条缺料重复占用同一在途。
         */
        private BigDecimal appliedInTransitNum;
        private BigDecimal suggestedTransferNum;
        private BigDecimal uncoveredNum;
        private String status;
        private Boolean evidenceComplete;
        private LocalDateTime evidenceAsOf;
        private String evidenceFingerprint;
        private List<SourceCandidate> sourceCandidates;
    }

    @Data
    @Accessors(chain = true)
    public static class SourceCandidate {
        private String sourceWarehouseCode;
        private String sourceWarehouseName;
        private BigDecimal usablePhysicalNum;
        private BigDecimal activeReservedNum;
        private BigDecimal safetyStockNum;
        private BigDecimal transferableNum;
        private BigDecimal suggestedNum;
        private Long routePolicyId;
        private String routePolicyCode;
        private String routePolicyName;
        private String routePolicyFingerprint;
        private String transportMode;
        private String urgencyLevel;
        private String routePolicyUrgencyLevel;
        private String calendarMode;
        private Boolean routePolicyMatched;
        private String routePolicyGap;
        private Integer transferLeadHours;
        private Integer transferLeadMinutes;
        private LocalDateTime estimatedArrivalTime;
        private List<StockAllocation> allocations;
    }

    @Data
    @Accessors(chain = true)
    public static class StockAllocation {
        private Long stockId;
        private String siteCode;
        private String siteName;
        private String containerCode;
        private String batchNo;
        private String supplierCode;
        private LocalDate validEndDate;
        private LocalDateTime stockInTime;
        private BigDecimal availableNum;
        private BigDecimal suggestedNum;
    }

    @Data
    public static class AgentCommand {
        private String requestId;
        private String factoryCode;
        private String businessType;
        private String businessNo;
        /**
         * PLAN中实际生产计划号，用于供应失效事件反查权威计划。
         */
        private String issueNo;
        private Long mrpId;
        private String targetWarehouseCode;
        private String materialCode;
        private BigDecimal expectedShortageNum;
        private BigDecimal expectedTargetInTransitNum;
        private String expectedEvidenceFingerprint;
        private String sourceWarehouseCode;
        private Long routePolicyId;
        private String routePolicyCode;
        private String routePolicyFingerprint;
        private String transportMode;
        private String urgencyLevel;
        private Boolean routePolicyRequired;
        private BigDecimal transferNum;
        private List<StockAllocation> allocations;
        private String reason;
    }

    @Data
    @Accessors(chain = true)
    public static class Receipt {
        private Long allocationBillId;
        private String billNo;
        private String allocationState;
        private String requestId;
        private String sourceWarehouseCode;
        private String targetWarehouseCode;
        private String materialCode;
        private BigDecimal transferNum;
    }
}
