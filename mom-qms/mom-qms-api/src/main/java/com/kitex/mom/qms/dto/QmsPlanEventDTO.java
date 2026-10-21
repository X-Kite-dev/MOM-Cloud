package com.kitex.mom.qms.dto;

import com.kitex.mom.qms.entity.QmsPlanEventOperationEntity;
import com.kitex.mom.qms.entity.QmsPlanEventOutboxEntity;
import com.kitex.mom.qms.entity.QmsPlanEventTargetEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

public final class QmsPlanEventDTO {
    private QmsPlanEventDTO() {
    }

    @Data
    public static class Query {
        private String eventId;
        private String inspectionType;
        private String sourceNo;
        private String mesTaskCode;
        private String dispositionKind;
        private String status;
    }

    @Data
    public static class ManualActionCommand {
        private Long outboxId;
        private String requestId;
        private String reason;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Detail extends QmsPlanEventOutboxEntity {
        private List<QmsPlanEventTargetEntity> targets;
        private List<QmsPlanEventOperationEntity> operations;
    }
}
