package com.kitex.mom.qms.agent.dto;

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
 * 指定物料批次的来料、过程、成品检验及NCR当前证据。
 */
public final class QmsBatchQualityTraceDTO {
    private QmsBatchQualityTraceDTO() {
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
        private Long inspectionTaskCount;
        private Long unqualifiedInspectionCount;
        private Long ncrDetailCount;
        private Long openNcrCount;
        private Integer returnedRecordCount;
        private List<QualityRecord> records = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    public static class QualityRecord {
        private String recordType;
        private Long recordId;
        private String recordNo;
        private String sourceTaskCode;
        private String orderNo;
        private String materialName;
        private String supplierCode;
        private String processCode;
        private String status;
        private String result;
        private BigDecimal quantity;
        private String quantityUnit;
        private String disposition;
        private String reason;
        private LocalDateTime occurredAt;
    }
}
