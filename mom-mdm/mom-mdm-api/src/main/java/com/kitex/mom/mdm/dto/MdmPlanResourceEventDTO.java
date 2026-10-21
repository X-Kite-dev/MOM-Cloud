package com.kitex.mom.mdm.dto;

import com.kitex.mom.mdm.entity.MdmPlanResourceEventOperationEntity;
import com.kitex.mom.mdm.entity.MdmPlanResourceEventOutboxEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

public final class MdmPlanResourceEventDTO {
    private MdmPlanResourceEventDTO() {
    }

    @Data
    public static class Query {
        private String eventId;
        private String sourceType;
        private String sourceNo;
        private String resourceType;
        private String resourceCode;
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
    public static class Detail extends MdmPlanResourceEventOutboxEntity {
        private List<MdmPlanResourceEventOperationEntity> operations;
    }
}
