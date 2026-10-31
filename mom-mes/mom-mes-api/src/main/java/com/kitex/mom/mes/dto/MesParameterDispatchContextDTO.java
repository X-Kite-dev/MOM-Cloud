package com.kitex.mom.mes.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/** 开工前也可核验的原派工参数依据，不使用实际RUN采样时间窗。 */
public final class MesParameterDispatchContextDTO {
    private MesParameterDispatchContextDTO() {}

    @Data
    @Accessors(chain = true)
    public static class Query {
        @NotBlank
        @Size(max = 100)
        private String factoryCode;

        @NotBlank
        @Size(max = 100)
        private String taskCode;

        @NotBlank
        @Size(max = 100)
        private String arrangeCode;

        @NotBlank
        @Size(max = 100)
        private String deviceCode;
    }

    @Data
    @Accessors(chain = true)
    public static class OptionsQuery {
        @NotBlank
        @Size(max = 100)
        private String factoryCode;

        @NotBlank
        @Size(max = 100)
        private String taskCode;

        @Size(max = 100)
        private String arrangeCode;

        @Size(max = 100)
        private String deviceCode;
    }

    public record ArrangementChoice(
            String arrangeCode,
            String processCode,
            String processName,
            String routeNodeCode,
            String stationCode,
            String status) {}

    public record Options(
            String schemaVersion,
            String factoryCode,
            String taskCode,
            String arrangeCode,
            String deviceCode,
            List<ArrangementChoice> arrangements,
            List<String> deviceCodes,
            boolean truncated,
            String blocker) {}

    @Data
    @Accessors(chain = true)
    public static class Evidence {
        private String schemaVersion;
        private String factoryCode;
        private String taskCode;
        private String arrangeCode;
        private String operationCode;
        private String processCode;
        private String routeNodeCode;
        private String stationCode;
        private String deviceCode;
        private String engineeringReleaseId;
        private Integer engineeringVersion;
        private String engineeringHash;
        private String snapshotFingerprint;
        private String nodeSnapshotFingerprint;
        private String standardNo;
        private Integer standardVersion;
        private String reworkMaterialPlanId;
        private String reworkEngineeringMode;
        private String reworkEngineeringScopeFingerprint;
        private String reworkEngineeringOverrideId;
        private String reworkEngineeringOverrideHash;
        private List<Parameter> parameters;
    }

    /** 原十进制文本；目标/上下限经过服务器数值校验，不能由下发页面替换。 */
    public record Parameter(
            String identifier,
            String unit,
            String targetValue,
            String lowerLimit,
            String upperLimit) {}
}
