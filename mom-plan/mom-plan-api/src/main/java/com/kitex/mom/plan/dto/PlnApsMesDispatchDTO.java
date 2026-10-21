package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/** 智能 APS 正式排程异步下达 MES 的不可变载荷和逐项回执。 */
public final class PlnApsMesDispatchDTO {

    private PlnApsMesDispatchDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "PLAN冻结的MES初始排程下达信封")
    public static class Envelope {
        private String contractVersion;
        private String requestId;
        private String caseId;
        private String scenarioId;
        private String selectedTaskId;
        private String publicationFingerprint;
        private String factoryCode;
        private String mrpAuthority;
        private String planningPolicyId;
        private Integer planningPolicyVersion;
        private String planningPolicyFingerprint;
        private String productionOrderSyncMode;
        private String formalPlanContractNo;
        private Integer formalPlanContractVersion;
        private String formalPlanContractFingerprint;
        private List<PlanItem> items;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "MES初始排程的单个PLAN计划项")
    public static class PlanItem {
        private String dispatchKey;
        private String issueId;
        private String issueNo;
        private List<String> issueIds;
        private List<String> issueNos;
        private String productCode;
        private String productName;
        private String workshopCode;
        private String workshopName;
        private String dispatchEvidenceFingerprint;
        private String operationEvidenceFingerprint;
        private Integer operationCount;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "MES初始排程单项回执")
    public static class ItemReceipt {
        private String dispatchKey;
        private String issueId;
        private String issueNo;
        private List<String> issueIds;
        private List<String> issueNos;
        private String status;
        private String message;
        private LocalDateTime processedTime;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "APS到MES下达队列视图")
    public static class DispatchView {
        private String outboxId;
        private String caseId;
        private String scenarioId;
        private String selectedTaskId;
        private String requestId;
        private String requestFingerprint;
        private String publicationFingerprint;
        private String status;
        private Integer attemptCount;
        private Integer dispatchedCount;
        private Integer failedCount;
        private LocalDateTime nextRetryTime;
        private String lastError;
        private List<ItemReceipt> receipts;
        private LocalDateTime queuedTime;
        private LocalDateTime sentTime;
        private LocalDateTime completedTime;
    }

    @Data
    @Schema(description = "人工重试MES下达命令")
    public static class RetryCommand {
        private String reason;
    }
}
