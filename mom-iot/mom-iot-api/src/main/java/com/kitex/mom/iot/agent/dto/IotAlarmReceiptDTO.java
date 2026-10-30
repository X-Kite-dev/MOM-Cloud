package com.kitex.mom.iot.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 原 IoT 告警在指定工厂、设备和参数下的只读状态回执。 */
public final class IotAlarmReceiptDTO {

    private IotAlarmReceiptDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "按原告警主键核对 IoT 领域状态")
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotBlank
        private String alarmEpisodeId;
        @NotBlank
        private String deviceCode;
        @NotBlank
        private String parameterIdentifier;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "不改变原告警状态机的观察时点快照")
    public static class Evidence {
        private String tenantId;
        private String factoryCode;
        private String alarmEpisodeId;
        private String deviceCode;
        private String parameterIdentifier;
        private String status;
        private String acknowledgedBy;
        private String verifiedBy;
        private LocalDateTime firstBreachTime;
        private LocalDateTime acknowledgedTime;
        private LocalDateTime recoveredTime;
        private LocalDateTime closedTime;
        private Long operationCount;
        private String latestOperationId;
        private String latestOperationAction;
        private LocalDateTime latestOperationTime;
        private Boolean lifecycleComplete;
        private LocalDateTime observedAt;
        private String factFingerprint;
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
    }
}
