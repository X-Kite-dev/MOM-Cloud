package com.kitex.mom.mes.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MES 报工与质量数量分析模型，供智能体只读问数使用。
 */
public final class MesReportingAnalysisDTO {

    private MesReportingAnalysisDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "MES 报工分析受控查询")
    public static class Query {
        @NotBlank
        private String factoryCode;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private String taskCode;
        private String produceTaskCode;
        /**
         * 仅在指定工单或工序任务时允许忽略时间范围，查询完整报工生命周期。
         */
        private Boolean allTime = false;
        @Min(1)
        @Max(50)
        private Integer limit = 20;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "MES 报工分析权威证据")
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String queryScope;
        private String taskCode;
        private String factoryCode;
        private LocalDateTime rangeStart;
        private LocalDateTime rangeEnd;
        private LocalDateTime observedAt;
        @Schema(description = "精确工单全生命周期数量与MES入库申请来源核验；不代表WMS实收")
        private MesReportQuantityConsistencyDTO.Evidence quantityIntegrity;
        private Summary summary;
        private List<ModeSummary> modeSummaries = new ArrayList<>();
        private List<UnitQuantitySummary> quantitySummaries = new ArrayList<>();
        private List<RecentReport> recentReports = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    public static class Summary {
        private Long effectiveReportCount;
        private Long reversedReportCount;
        private Long automaticReportCount;
        private Long manualReportCount;
        private Long unknownSourceCount;
        private Long distinctTaskCount;
    }

    @Data
    @Accessors(chain = true)
    public static class ModeSummary {
        private String reportMode;
        private String reportModeName;
        private Long reportCount;
    }

    @Data
    @Accessors(chain = true)
    public static class UnitQuantitySummary {
        private String taskCode;
        private String produceTaskCode;
        private String reportUnit;
        @Schema(description = "有效产出量，使用受控纠正后的当前有效数量；不是原始发生量")
        private BigDecimal reportedQuantity;
        private BigDecimal originalQuantity;
        private BigDecimal correctedQuantity;
        @Schema(description = "当前有效数量无法核定的报工笔数，不用零替代未知")
        private Long unverifiedReportCount;
        private BigDecimal qualityReleasedQuantity;
        private BigDecimal qualityPendingQuantity;
        private BigDecimal qualityRejectedQuantity;
    }

    @Data
    @Accessors(chain = true)
    public static class RecentReport {
        private Long reportId;
        private String requestId;
        private String status;
        private String taskCode;
        private String produceTaskCode;
        private String materialCode;
        private String materialName;
        private String reportMode;
        private String sourceType;
        @Schema(description = "当前有效产出；ROUTE_RETURN为执行记录，不作为新产出")
        private BigDecimal reportQuantity;
        private BigDecimal originalQuantity;
        private BigDecimal correctedQuantity;
        private String executionKind;
        private String reportUnit;
        private BigDecimal qualityReleasedQuantity;
        private BigDecimal qualityPendingQuantity;
        private BigDecimal qualityRejectedQuantity;
        private String qualityQuantityStatus;
        private LocalDateTime reportTime;
        private String workerCode;
        private String workerName;
    }
}
