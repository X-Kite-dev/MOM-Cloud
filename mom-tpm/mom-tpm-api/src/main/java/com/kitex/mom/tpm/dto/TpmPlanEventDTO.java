package com.kitex.mom.tpm.dto;

import com.kitex.mom.tpm.entity.TpmPlanEventDeliveryOperationEntity;
import com.kitex.mom.tpm.entity.TpmPlanEventOutboxEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

public final class TpmPlanEventDTO {
    private TpmPlanEventDTO() {
    }

    @Data
    public static class Query {
        private String eventId;
        private String sourceType;
        private String sourceNo;
        private String deviceCode;
        private String status;
    }

    @Data
    public static class ForecastCommand {
        private Long outboxId;
        private LocalDateTime expectedRecoveryTime;
        private String requestId;
        private String reason;
    }

    @Data
    public static class ManualActionCommand {
        private Long outboxId;
        private String requestId;
        private String reason;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Detail extends TpmPlanEventOutboxEntity {
        private List<TpmPlanEventDeliveryOperationEntity> operations;
    }
}
