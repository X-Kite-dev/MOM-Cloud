package com.kitex.mom.wms.dto;

import com.kitex.mom.wms.entity.WmsPlanShortageEventOperationEntity;
import com.kitex.mom.wms.entity.WmsPlanShortageEventOutboxEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

public final class WmsPlanShortageEventDTO {
    private WmsPlanShortageEventDTO() {
    }

    @Data
    public static class Query {
        private String sourceType;
        private String sourceNo;
        private String allocationBillNo;
        private String issueNo;
        private String materialCode;
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
    public static class Detail extends WmsPlanShortageEventOutboxEntity {
        private List<WmsPlanShortageEventOperationEntity> operations;
    }
}
