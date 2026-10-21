package com.kitex.mom.mes.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 原料批次实际消耗至生产报工和包装对象的正向追溯证据。
 */
public final class MesBatchTraceDTO {
    private MesBatchTraceDTO() {
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
        @Max(50)
        private Integer detailLimit = 20;
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
        private Long matchedReportCount;
        private Integer returnedReportCount;
        private Long effectiveReturnedCount;
        private Long reversedReturnedCount;
        private List<AffectedReport> affectedReports = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    public static class AffectedReport {
        private Long reportId;
        private String reportRequestId;
        private String status;
        private String taskCode;
        private String operationTaskCode;
        private String arrangeCode;
        private String outputMaterialCode;
        private String outputMaterialName;
        private BigDecimal reportedQuantity;
        private String reportUnit;
        private String reportMode;
        private String transferCode;
        private String qualityQuantityStatus;
        private BigDecimal releasedQuantity;
        private BigDecimal pendingQuantity;
        private BigDecimal rejectedQuantity;
        private LocalDateTime reportTime;
        private List<String> packageCodes = new ArrayList<>();
        private List<String> gaps = new ArrayList<>();
    }
}
