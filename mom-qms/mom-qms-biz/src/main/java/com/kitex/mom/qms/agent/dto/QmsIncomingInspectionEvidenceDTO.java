package com.kitex.mom.qms.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PMS已冻结来料质检任务的QMS独立观察结果。
 */
public final class QmsIncomingInspectionEvidenceDTO {

    private QmsIncomingInspectionEvidenceDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotEmpty
        private List<String> taskIds;
    }

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private LocalDateTime observedAt;
        private List<Task> tasks;
        private List<String> dataGaps;
        private List<String> limitations;
        private List<String> evidenceRefs;
        private String factFingerprint;
    }

    @Data
    @Accessors(chain = true)
    public static class Task {
        private String taskId;
        private String taskCode;
        private String purchaseOrderDetailId;
        private String materialCode;
        private String materialName;
        private String materialBatch;
        private String supplierCode;
        private String status;
        private String result;
        private BigDecimal submittedQuantity;
        private BigDecimal usableQuantity;
        private BigDecimal handledQuantity;
        private String handleResult;
        private Boolean terminal;
        private Boolean acceptedForInbound;
        private LocalDateTime latestInspectionTime;
    }
}
