package com.kitex.mom.mes.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MES 为 IoT 参数取证提供的实际设备生产时间窗。
 */
public final class MesProcessTelemetryScopeDTO {

    private MesProcessTelemetryScopeDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "工单实际设备生产时间窗受控查询")
    public static class Query {
        @NotBlank
        @Size(max = 100)
        private String factoryCode;

        @NotBlank
        @Size(max = 100)
        private String taskCode;

        /**
         * 缺省只接受历史V1；新消费者显式声明ROUTE_NODE_V2才可读取节点冻结。
         */
        @Size(max = 40)
        private String acceptedSnapshotSchemaVersion;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "工单实际设备生产时间窗证据")
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private String taskCode;
        private LocalDateTime observedAt;
        private Integer sourceSegmentCount;
        private String processParameterStandardNo;
        private Integer processParameterStandardVersion;
        private String processParameterSnapshotFingerprint;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String snapshotSchemaVersion;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String contentSchemaVersion;
        private List<ExecutionWindow> windows = new ArrayList<>();
        private List<FrozenParameterStandard> frozenParameterStandards = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    public static class ExecutionWindow {
        private Long executionSegmentId;
        private String arrangeCode;
        private String operationTaskCode;
        private String processCode;
        /**
         * V2必须来自实际工序任务且与原冻结路线一致，V1保持为空。
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String routeNodeCode;
        private String stationCode;
        private String deviceCode;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private String sourceStatus;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "生产工单下达时冻结的工序参数标准")
    public static class FrozenParameterStandard {
        private Long sourceItemId;
        private String processCode;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String routeNodeCode;
        private String processName;
        private String identifier;
        private String parameterName;
        private String dataType;
        private String unit;
        private BigDecimal targetValue;
        private BigDecimal lowerLimit;
        private BigDecimal upperLimit;
        private BigDecimal warningLowerLimit;
        private BigDecimal warningUpperLimit;
        private Boolean required;
        private String aggregationMode;
        private Integer minimumSampleCount;
        private BigDecimal maximumNonGoodRatePct;
        private Integer continuousDeviationSeconds;
    }
}
