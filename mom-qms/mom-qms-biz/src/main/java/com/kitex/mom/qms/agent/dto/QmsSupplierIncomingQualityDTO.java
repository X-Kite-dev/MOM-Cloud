package com.kitex.mom.qms.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 指定供应商—物料在有限时间窗内的来料质量事实。
 */
public final class QmsSupplierIncomingQualityDTO {

    private QmsSupplierIncomingQualityDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotBlank
        private String supplierCode;
        @NotBlank
        private String materialCode;
        @NotBlank
        private String materialUnit;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Integer limit;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private String supplierCode;
        private String materialCode;
        private String materialUnit;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private LocalDateTime observedAt;
        private Long totalTaskCount;
        private Long finishedTaskCount;
        private Long acceptedTaskCount;
        private Long unqualifiedTaskCount;
        private BigDecimal submittedQuantity;
        private BigDecimal usableQuantity;
        private BigDecimal rejectedQuantity;
        private BigDecimal finishedTaskPassRate;
        private Boolean truncated;
        private List<InspectionTask> tasks;
        private List<Nonconformance> nonconformances;
        private List<Capa> capas;
        private List<DefectPattern> recurringDefects;
        private List<String> dataGaps;
        private List<String> limitations;
        private List<String> evidenceRefs;
        private String factFingerprint;
    }

    @Data
    @Accessors(chain = true)
    public static class InspectionTask {
        private String taskId;
        private String taskCode;
        private String purchaseOrderDetailId;
        private String batchNo;
        private String status;
        private String result;
        private BigDecimal submittedQuantity;
        private BigDecimal usableQuantity;
        private String inspectionLevel;
        private String materialAuthorizationNo;
        private LocalDateTime latestInspectionTime;
    }

    @Data
    @Accessors(chain = true)
    public static class Nonconformance {
        private String ncrId;
        private String ncrNo;
        private String taskId;
        private String batchNo;
        private String badType;
        private String status;
        private BigDecimal quantity;
        private String capaId;
        private String capaStatus;
    }

    @Data
    @Accessors(chain = true)
    public static class Capa {
        private String capaId;
        private String capaNo;
        private String sourceNcrId;
        private String title;
        private String status;
        private Integer recurrenceCount;
        private Integer riskPriorityNo;
        private LocalDateTime dueTime;
        private String effectivenessResult;
        private LocalDateTime closedTime;
    }

    @Data
    @Accessors(chain = true)
    public static class DefectPattern {
        private String defectType;
        private Long occurrenceCount;
        private Long affectedBatchCount;
        private List<String> ncrIds;
    }
}
