package com.kitex.mom.qms.agent.dto;

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
 * QMS 来料、过程和成品检验概览模型，供智能体只读问数使用。
 */
public final class QmsInspectionOverviewDTO {

    private QmsInspectionOverviewDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "QMS 检验概览受控查询")
    public static class Query {
        @NotBlank
        private String factoryCode;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        @Min(1)
        @Max(50)
        private Integer limit = 20;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "QMS 检验概览权威证据")
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private LocalDateTime rangeStart;
        private LocalDateTime rangeEnd;
        private LocalDateTime observedAt;
        private Summary summary;
        private List<TypeSummary> typeSummaries = new ArrayList<>();
        private List<ExceptionTask> exceptionTasks = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    public static class Summary {
        private Long activityTaskCount;
        private Long waitingTaskCount;
        private Long runningTaskCount;
        private Long unhandledTaskCount;
        private Long finishedTaskCount;
        private Long qualifiedTaskCount;
        private Long unqualifiedTaskCount;
        private Long exemptionTaskCount;
        private Long emergencyReleaseTaskCount;
        private Long concessionReceiveTaskCount;
        private Long canceledTaskCount;
        private BigDecimal strictTaskQualificationRate;
    }

    @Data
    @Accessors(chain = true)
    public static class TypeSummary {
        private String inspectionType;
        private String inspectionTypeName;
        private Long activityTaskCount;
        private Long waitingTaskCount;
        private Long runningTaskCount;
        private Long unhandledTaskCount;
        private Long finishedTaskCount;
        private Long qualifiedTaskCount;
        private Long unqualifiedTaskCount;
    }

    @Data
    @Accessors(chain = true)
    public static class ExceptionTask {
        private String inspectionType;
        private Long taskId;
        private String taskCode;
        private String orderNo;
        private String materialCode;
        private String materialName;
        private String batchNo;
        private String supplierCode;
        private String supplierName;
        private String processCode;
        private String processName;
        private String status;
        private String statusName;
        private String result;
        private String resultName;
        private Integer inspectionQuantity;
        private Integer usableQuantity;
        private Integer handledQuantity;
        private LocalDateTime lastInspectionTime;
        private LocalDateTime createTime;
    }
}
