package com.kitex.mom.mes.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MES工单计划资源与现场实际执行资源的一致性证据契约。
 */
public final class MesResourceExecutionConformanceDTO {

    private MesResourceExecutionConformanceDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "MES工单资源执行一致性受控查询")
    public static class Query {
        @NotBlank
        @Size(max = 100)
        private String factoryCode;

        @NotBlank
        @Size(max = 100)
        private String taskCode;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "MES工单资源执行一致性权威证据")
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private String taskCode;
        private LocalDateTime observedAt;
        private Summary summary;
        private List<OperationExecution> operations = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private String evidenceFingerprint;
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    public static class Summary {
        private Integer operationCount;
        private Integer dispatchCount;
        private Integer startedDispatchCount;
        private Integer executionSegmentCount;
        private Integer toolingExecutionCount;
        private Integer conformantOperationCount;
        private Integer deviationOperationCount;
        private Integer unknownOperationCount;
        /**
         * CONFORMANT/NON_CONFORMANT/PARTIAL/NO_EXECUTION。
         */
        private String overallStatus;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "工序级计划资源与实际执行资源核对")
    public static class OperationExecution {
        private Long operationId;
        private String operationTaskCode;
        private String processCode;
        private String processName;
        private Integer operationSeq;
        private String operationStatus;
        private String plannedWorksiteCode;
        private String plannedDeviceCode;
        private String plannedWorkerCode;
        private List<String> plannedToolingCodes = new ArrayList<>();
        private LocalDateTime plannedStartTime;
        private LocalDateTime plannedEndTime;
        private List<String> actualWorksiteCodes = new ArrayList<>();
        private List<String> actualDeviceCodes = new ArrayList<>();
        private List<String> actualWorkerCodes = new ArrayList<>();
        private List<String> actualToolingCodes = new ArrayList<>();
        private LocalDateTime actualStartTime;
        private LocalDateTime actualEndTime;
        private Long startDeviationMinutes;
        private Long finishDeviationMinutes;
        private Boolean worksiteMatched;
        private Boolean deviceMatched;
        private Boolean workerMatched;
        private Boolean toolingMatched;
        /**
         * CONFORMANT/DEVIATION/UNKNOWN/NOT_STARTED。
         */
        private String conformanceStatus;
        private List<DispatchExecution> dispatches = new ArrayList<>();
        private List<String> issues = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "派工及其连续生产区间和工装执行事实")
    public static class DispatchExecution {
        private Long arrangeId;
        private String arrangeCode;
        private String arrangeStatus;
        private String assignedWorkerCodes;
        private String actualWorksiteCode;
        private LocalDateTime dispatchTime;
        private LocalDateTime arrangeStartTime;
        private LocalDateTime arrangeFinishTime;
        private List<ExecutionSegment> segments = new ArrayList<>();
        private List<ToolingExecution> toolings = new ArrayList<>();
        private Boolean segmentsTruncated;
        private Boolean toolingsTruncated;
    }

    @Data
    @Accessors(chain = true)
    public static class ExecutionSegment {
        private Long segmentId;
        private Integer segmentNo;
        private String status;
        private String closeReason;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Long durationSeconds;
        private String worksiteCode;
        private String deviceCode;
        private List<String> workerCodes = new ArrayList<>();
        private Boolean calibrationEligible;
        private String exclusionReason;
    }

    @Data
    @Accessors(chain = true)
    public static class ToolingExecution {
        private Long toolingExecutionId;
        private String toolingCode;
        private String toolingName;
        private String status;
        private String checkInWorkerCode;
        private LocalDateTime checkInTime;
        private LocalDateTime startTime;
        private LocalDateTime releaseTime;
    }
}
