package com.kitex.mom.wms.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 单条实时库存的权威调查证据。此合同只描述当前事实，不批准质量放行、调拨或报废。
 */
public final class WmsStockDispositionDTO {

    private WmsStockDispositionDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "按实时库存主键精确重采的请求")
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotBlank
        @Pattern(regexp = "[1-9][0-9]{0,18}")
        private String stockId;
        @Min(1)
        @Max(3650)
        private Integer nearExpiryDays = 30;
        @Min(1)
        @Max(3650)
        private Integer slowMovingDays = 90;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "WMS当前库存行、活动预留与质量效期的只读证据")
    public static class Evidence {
        private String toolCode;
        private String factoryCode;
        private String stockId;
        private LocalDateTime observedAt;
        private String factFingerprint;
        private String metricDefinition;
        private Stock stock;
        private Summary summary;
        private List<String> exceptionTypes = new ArrayList<>();
        private List<String> limitations = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class Stock {
        private String warehouseCode;
        private String warehouseName;
        private String siteCode;
        private String siteName;
        private String containerCode;
        private String materialCode;
        private String materialName;
        private String materialBatch;
        private String materialBarcode;
        private String unitCode;
        private String supplierCode;
        private String supplierName;
        private BigDecimal physicalNum;
        private BigDecimal productionReservedNum;
        private BigDecimal salesReservedNum;
        private BigDecimal availableNum;
        private String qualityStatus;
        private String qualityResult;
        private String qualityHoldNo;
        private String qualitySourceType;
        private String qualitySourceId;
        private Integer lockStatus;
        private Boolean locked;
        private Boolean qualityUsable;
        private Boolean expired;
        private Boolean nearExpiry;
        private Boolean slowMoving;
        private LocalDateTime inTime;
        private LocalDate validEndDate;
        private LocalDateTime stockUpdatedAt;
    }

    @Data
    @Accessors(chain = true)
    public static class Summary {
        private Long stockRowCount;
        private Long exceptionCount;
        private BigDecimal physicalNum;
        private BigDecimal productionReservedNum;
        private BigDecimal salesReservedNum;
        private BigDecimal availableNum;
    }
}
