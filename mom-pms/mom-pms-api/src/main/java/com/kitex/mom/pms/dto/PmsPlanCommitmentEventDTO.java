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
        /** 从 APS 回到原调查时，限定在原采购单内查询本次改期投递主键。 */
        private Long outboxId;
        private Long pmsOrderId;
        /** 从履约调查打开承诺台账时，按原采购订单行筛选排程投递记录。 */
        private Long orderDetailId;
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
