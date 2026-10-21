package com.kitex.mom.plan.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class PlanMrpTransferSuggestionDTO {
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

    @Data
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
}
