package com.kitex.mom.iot.agent.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 工艺参数异常的逐点证据合同；数值由 IoT 确定性计算而非模型生成。 */
public final class IotProcessDeviationDTO {

    private IotProcessDeviationDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "工艺参数异常受控取证请求")
    public static class Query {
        @NotBlank
        @Size(max = 100)
        private String factoryCode;
        @NotBlank
        private String sourceType;
        @Size(max = 100)
        private String taskCode;
        private Long alarmEpisodeId;
        @Size(max = 100)
        private String deviceCode;
        @Size(max = 100)
        private String routeNodeCode;
        @Size(max = 100)
        private String parameterIdentifier;
        /** 仅在原业务处置完成后的效果观察使用；仍须由 MES 实际 RUN 生产段限定。 */
        private LocalDateTime observationStartTime;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "生产节点逐点偏差及业务来源的冻结证据")
    public static class Evidence {
        private String toolCode;
        private String factoryCode;
        private String sourceType;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long alarmEpisodeId;
        private String alarmStatus;
        private String taskCode;
        /** MES 原工单产品编码；仅作跨工单可比性验证，不由IoT采样推断。 */
        private String productCode;
        private String routeNodeCode;
        private String processCode;
        private String deviceCode;
        private String parameterIdentifier;
        private String parameterName;
        private String unit;
        private BigDecimal lowerLimit;
        private BigDecimal upperLimit;
        private Integer continuousDeviationSeconds;
        private String processParameterStandardNo;
        private Integer processParameterStandardVersion;
        private String processParameterSnapshotFingerprint;
        private String mesScopeFingerprint;
        private LocalDateTime observedAt;
        /** 本次实际参与指标计算的生产时间范围，不能与整张工单的累计范围混淆。 */
        private LocalDateTime measurementStartTime;
        private LocalDateTime measurementEndTime;
        private Long measurementWindowSeconds;
        /** 有未结束的 MES RUN 段时证据仍会增长，不能作为最终效果快照。 */
        private Boolean measurementWindowsClosed;
        private Long totalSampleCount;
        private Long goodSampleCount;
        private Long nonGoodSampleCount;
        private Long invalidNumericCount;
        private Long outOfLimitSampleCount;
        private Long cumulativeOutOfLimitSeconds;
        private Long longestOutOfLimitSeconds;
        private Integer deviationEpisodeCount;
        private BigDecimal deviationArea;
        private BigDecimal trendSlopePerHour;
        private Long maximumSampleGapSeconds;
        private String evidenceStatus;
        private List<Sample> trend = new ArrayList<>();
        private List<DeviationEpisode> episodes = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private String factFingerprint;
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    public static class Sample {
        private Long propertyId;
        private LocalDateTime collectTime;
        private BigDecimal numericValue;
        private String qualityCode;
        private Boolean outOfLimit;
        /** 新实际 RUN 生产段的首个采样点；绘图及区段统计不得跨此点连线。 */
        private Boolean segmentStart;
    }

    @Data
    @Accessors(chain = true)
    public static class DeviationEpisode {
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Long durationSeconds;
        private BigDecimal area;
        private Long firstPropertyId;
        private Long lastPropertyId;
        private Integer sampleCount;
    }
}
