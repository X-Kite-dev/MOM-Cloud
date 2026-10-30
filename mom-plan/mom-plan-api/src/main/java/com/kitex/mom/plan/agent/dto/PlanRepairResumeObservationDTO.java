package com.kitex.mom.plan.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 同一批 MES 真实复工事件在 PLAN 收件及观察账中的只读状态。
 * PLAN 观察不等于生产现场复工审批，也不等于已发布排程被改写。
 */
public final class PlanRepairResumeObservationDTO {

    private PlanRepairResumeObservationDTO() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotEmpty
        private List<String> inboxIds;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "PLAN 收件箱与维修复工观察账，按精确收件主键查询")
    public static class Evidence {
        private String factoryCode;
        private LocalDateTime observedAt;
        private Integer requestedCount;
        private Integer processedCount;
        private Integer verifiedObservationCount;
        private String factFingerprint;
        private List<Item> items = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class Item {
        private String inboxId;
        private String eventId;
        private String eventStatus;
        private Boolean observationVerified;
        private String observationId;
        private String mesOutboxId;
        private String stopId;
        private String sourceFingerprint;
        private String planNo;
        private String matchStatus;
        private String resourceCaseId;
        private LocalDateTime observedTime;
    }
}
