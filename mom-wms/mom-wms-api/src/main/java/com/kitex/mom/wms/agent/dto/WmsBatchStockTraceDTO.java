package com.kitex.mom.wms.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 指定物料批次在当前WMS实时库存中的位置、质量及锁定证据。
 */
public final class WmsBatchStockTraceDTO {
    private WmsBatchStockTraceDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotBlank
        @Size(max = 200)
        private String materialCode;
        @NotBlank
        @Size(max = 200)
        private String batchNo;
        @Min(1)
        @Max(100)
        private Integer detailLimit = 50;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private String materialCode;
        private String batchNo;
        private LocalDateTime observedAt;
        private Long positiveStockRowCount;
        private Integer returnedStockRowCount;
        private Long lockedRowCount;
        private Long qualityRestrictedRowCount;
        private List<UnitQuantity> unitQuantities = new ArrayList<>();
        private List<StockPosition> stockPositions = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    public static class UnitQuantity {
        private String unit;
        private BigDecimal physicalQuantity;
        /**
         * 当前未被其他质量单占用、可由召回HOLD命令立即隔离的数量。
         */
        private BigDecimal recallFreezableQuantity;
        private BigDecimal lockedQuantity;
        private BigDecimal qualityRestrictedQuantity;
    }

    @Data
    @Accessors(chain = true)
    public static class StockPosition {
        private Long stockId;
        private String materialName;
        private String supplierCode;
        private String supplierName;
        private String warehouseCode;
        private String warehouseName;
        private String locationCode;
        private String locationName;
        private String containerCode;
        private BigDecimal physicalQuantity;
        private String unit;
        private Integer lockStatus;
        private String qualityStatus;
        private String qualityResult;
        private String qualityHoldNo;
        private LocalDate validEndDate;
        private LocalDateTime inboundTime;
    }
}
