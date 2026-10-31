package com.kitex.mom.iot.agent.dto;

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

/** 工单实际生产区间内的 IoT 工艺参数时序证据。 */
public final class IotProcessTelemetryDTO {

    private IotProcessTelemetryDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "工单 IoT 工艺参数受控查询")
    public static class Query {
        @NotBlank
        @Size(max = 100)
        private String factoryCode;

        @NotBlank
        @Size(max = 100)
        private String taskCode;

        @Schema(description = "缺省只接受历史V1；节点冻结消费者必须显式声明ROUTE_NODE_V2")
        @Size(max = 40)
        private String acceptedSnapshotSchemaVersion;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "工单 IoT 工艺参数权威证据")
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private String taskCode;
        private LocalDateTime observedAt;
        private String processParameterStandardNo;
        private Integer processParameterStandardVersion;
        private String processParameterSnapshotFingerprint;
        @Schema(description = "冻结协议：LEGACY_PROCESS_V1或ROUTE_NODE_V2")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String snapshotSchemaVersion;
        @Schema(description = "冻结来源的参数内容协议：LEGACY_PROCESS_V1或NODE_V2")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String contentSchemaVersion;
        @Schema(description = "CONFORMANT、NON_CONFORMANT、PARTIAL或NO_STANDARD")
        private String processComplianceStatus;
        private Summary summary;
        private List<DeviceWindow> deviceWindows = new ArrayList<>();
        private List<ParameterEvidence> parameters = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    public static class Summary {
        private Integer executionSegmentCount;
        private Integer mergedDeviceWindowCount;
        private Integer deviceCount;
        private Integer parameterCount;
        private Long totalSampleCount;
        private Long goodSampleCount;
        private Long nonGoodSampleCount;
        private Long numericGoodSampleCount;
        private Integer outOfModelEnvelopeParameterCount;
        private Integer unknownModelEnvelopeParameterCount;
        private Integer requiredParameterGapCount;
        private Integer frozenStandardParameterCount;
        private Integer conformantParameterCount;
        private Integer nonConformantParameterCount;
        private Integer insufficientEvidenceParameterCount;
        private Integer undefinedStandardParameterCount;
        private Integer missingRequiredStandardParameterCount;
    }

    @Data
    @Accessors(chain = true)
    public static class DeviceWindow {
        private String deviceCode;
        private String deviceName;
        private String productCode;
        @Schema(description = "ROUTE_NODE_V2按真实执行节点分桶；历史V1为空")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String routeNodeCode;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private List<String> processCodes = new ArrayList<>();
        private List<Long> executionSegmentIds = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class ParameterEvidence {
        private String deviceCode;
        private String deviceName;
        private String productCode;
        @Schema(description = "ROUTE_NODE_V2按设备、节点和参数标识符聚合；历史V1为空")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String routeNodeCode;
        private List<String> processCodes = new ArrayList<>();
        private String identifier;
        private String parameterName;
        private String dataType;
        private String unit;
        @Schema(description = "numeric GOOD采样中已证明唯一的实际单位；未知或混合为空")
        private String sampleUnit;
        @Schema(description = "KNOWN、MIXED、MISSING、UNKNOWN或NO_SAMPLES")
        private String sampleUnitStatus;
        private String standardUnit;
        private LocalDateTime firstCollectTime;
        private LocalDateTime lastCollectTime;
        private Long sampleCount;
        private Long goodSampleCount;
        private Long nonGoodSampleCount;
        private Long numericGoodSampleCount;
        private BigDecimal minimumValue;
        private BigDecimal maximumValue;
        private BigDecimal averageValue;
        private BigDecimal modelMinimum;
        private BigDecimal modelMaximum;
        @Schema(description = "IOT_PRODUCT_MODEL 表示设备物模型数据包络，不等同于工艺卡参数标准")
        private String envelopeSource;
        private Boolean withinModelEnvelope;
        private List<String> standardProcessCodes = new ArrayList<>();
        private List<Long> standardSourceItemIds = new ArrayList<>();
        private BigDecimal standardTargetValue;
        private BigDecimal standardLowerLimit;
        private BigDecimal standardUpperLimit;
        private BigDecimal standardWarningLowerLimit;
        private BigDecimal standardWarningUpperLimit;
        private String standardAggregationMode;
        private Integer standardMinimumSampleCount;
        private BigDecimal standardMaximumNonGoodRatePct;
        @Schema(description = "MES_FROZEN_MDM表示使用工单下达时冻结的MDM标准")
        private String processStandardSource;
        @Schema(description = "CONFORMANT、NON_CONFORMANT、INSUFFICIENT_EVIDENCE、NOT_DEFINED或AMBIGUOUS_STANDARD")
        private String processComplianceStatus;
        private List<String> deviations = new ArrayList<>();
    }
}
