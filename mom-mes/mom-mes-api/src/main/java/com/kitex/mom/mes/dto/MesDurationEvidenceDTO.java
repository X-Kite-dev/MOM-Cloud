package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * MES提供给标准工时校准的只读、可重采执行证据。
 */
public final class MesDurationEvidenceDTO {
    private MesDurationEvidenceDTO() {
    }

    @Data
    public static class Query {
        private String factoryCode;
        private LocalDateTime fromTime;
        private LocalDateTime toTime;
        private String processCode;
        private String deviceCode;
        private Integer limit;
    }

    @Data
    @Accessors(chain = true)
    public static class Bundle {
        private LocalDateTime generatedTime;
        private String evidenceFingerprint;
        private List<OperationSample> operationSamples;
        private List<ChangeoverSample> changeoverSamples;
        private Integer excludedOperationCount;
        private Integer excludedChangeoverCount;
    }

    @Data
    @Accessors(chain = true)
    public static class OperationSample {
        private String sampleId;
        private Long arrangeId;
        private String arrangeCode;
        private String taskCode;
        private String produceTaskCode;
        private String processCode;
        private String materialCode;
        private String stationCode;
        private String deviceCode;
        private String workerCodes;
        private BigDecimal completedQuantity;
        private LocalDateTime actualStartTime;
        private LocalDateTime actualEndTime;
        private Long activeSeconds;
        private Long wallClockSeconds;
        private Long excludedPauseSeconds;
        private LocalDateTime plannedStartTime;
        private LocalDateTime plannedEndTime;
        private String apsOperationSnapshot;
        private Integer segmentCount;
        private Boolean sourceEligible;
        private String exclusionReason;
        private String sourceFingerprint;
    }

    @Data
    @Accessors(chain = true)
    public static class ChangeoverSample {
        private String sampleId;
        private Long executionId;
        private String requestId;
        private String worksiteCode;
        private String deviceCode;
        private String fromToolingCode;
        private String toToolingCode;
        private LocalDateTime startedTime;
        private LocalDateTime completedTime;
        private Long durationSeconds;
        private Boolean sourceEligible;
        private String exclusionReason;
        private String sourceFingerprint;
    }
}
