package com.kitex.mom.pms.dto;

import com.kitex.mom.pms.entity.PmsPlanCommitmentEventOperationEntity;
import com.kitex.mom.pms.entity.PmsPlanCommitmentEventOutboxEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

public final class PmsPlanCommitmentEventDTO {
    private PmsPlanCommitmentEventDTO() {
    }

    @Data
    public static class Query {
        private Long pmsOrderId;
        private String pmsPlanNo;
        private String planDemandNo;
        private String materialCode;
        private String sourceType;
        private String status;
    }

    @Data
    public static class ForecastCommand {
        private Long outboxId;
        private LocalDateTime expectedSupplyTime;
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
    public static class Detail extends PmsPlanCommitmentEventOutboxEntity {
        private List<PmsPlanCommitmentEventOperationEntity> operations;
    }
}
