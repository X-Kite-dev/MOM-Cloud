package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 按维修任务归集 MES 当前复产审批、真实复工及 PLAN 收件事实；仅只读，不执行复产。
 */
public final class MesRepairAgentReleaseEvidence {

    private MesRepairAgentReleaseEvidence() {
    }

    @Data
    @Accessors(chain = true)
    public static class Query {
        @NotBlank
        private String factoryCode;
        @NotBlank
        private String repairTaskId;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "同维修任务涉及的停工派工、正式复产审批及独立复工事件")
    public static class Result {
        private String factoryCode;
        private String repairTaskId;
        private LocalDateTime observedAt;
        private Integer affectedStopCount;
        private Integer currentStopCount;
        private Integer approvedCurrentStopCount;
        /**
         * 仅统计经 MES 真实开工切段来源校验的复工事件。
         */
        private Integer verifiedResumeEventCount;
        /**
         * 仅统计已获 PLAN 收件回执的事件，不代表 PLAN 已完成业务处理。
         */
        private Integer planReceivedEventCount;
        private Boolean releaseApplicable;
        private Boolean allCurrentStopsApproved;
        private String factFingerprint;
        private List<Stop> stops = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        /**
         * 真实复工与 PLAN 收件独立缺口，不改变复产批准判定。
         */
        private List<String> resumeDataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class Stop {
        private String stopId;
        private String arrangeCode;
        private String deviceCode;
        private Boolean currentStop;
        private Boolean approved;
        private String latestReleaseId;
        private String latestAction;
        private String recoveryReceiptId;
        private String resumeOutboxId;
        private String resumeEventId;
        private String resumeSourceFingerprint;
        private LocalDateTime resumedAt;
        private String planDeliveryStatus;
        private String planInboxId;
        private String planEventStatus;
    }
}
