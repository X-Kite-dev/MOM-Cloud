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
 * 工艺参数异常调查使用的精确生产范围；标准始终取工单下达时的冻结快照。
 */
public final class MesProcessDeviationScopeDTO {

    private MesProcessDeviationScopeDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "按生产工单或已核验IoT告警定位实际执行节点")
    public static class Query {
        @NotBlank
        @Size(max = 100)
        private String factoryCode;
        @NotBlank
        private String sourceType;
        @Size(max = 100)
        private String taskCode;
        @Size(max = 100)
        private String deviceCode;
        private LocalDateTime eventTime;
        @Size(max = 100)
        private String routeNodeCode;
        @Size(max = 100)
        private String parameterIdentifier;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "唯一工单、节点、设备和参数对应的冻结标准与生产窗口")
    public static class Evidence {
        private String toolCode;
        private String factoryCode;
        private String sourceType;
        private String taskCode;
        /**
         * 原 MES 工单产品编码，用于跨工单效果比较的产品同一性核验。
         */
        private String productCode;
        private String routeNodeCode;
        private String processCode;
        private String deviceCode;
        private String parameterIdentifier;
        private String processParameterStandardNo;
        private Integer processParameterStandardVersion;
        private String processParameterSnapshotFingerprint;
        private MesProcessTelemetryScopeDTO.FrozenParameterStandard frozenStandard;
        private List<MesProcessTelemetryScopeDTO.ExecutionWindow> windows = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private String factFingerprint;
        private Boolean truncated;
    }
}
