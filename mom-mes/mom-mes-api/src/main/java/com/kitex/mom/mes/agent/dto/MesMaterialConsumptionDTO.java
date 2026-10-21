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
 * 指定生产工单的有效报工实际物料消耗证据。
 */
public final class MesMaterialConsumptionDTO {

    private MesMaterialConsumptionDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "MES生产工单实际物料消耗查询")
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotBlank
        private String taskCode;
        @Min(1)
        @Max(50)
        private Integer limit = 20;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "MES生产工单实际物料消耗权威证据")
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private String taskCode;
        private LocalDateTime observedAt;
        private Summary summary;
        private List<MaterialConsumption> consumptions = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    public static class Summary {
        private Long effectiveReportCount;
        private Long manualConsumptionRecordCount;
        private Long backflushConsumptionRecordCount;
        private Long substituteConsumptionRecordCount;
        private Long distinctMaterialBatchCount;
    }

    @Data
    @Accessors(chain = true)
    public static class MaterialConsumption {
        private String sourceType;
        private String requirementMaterialCode;
        private String actualMaterialCode;
        private String actualMaterialName;
        private String batchNo;
        private String unit;
        private BigDecimal requirementQuantity;
        private BigDecimal consumptionQuantity;
        private Boolean substituteFlag;
        private Long recordCount;
    }
}
