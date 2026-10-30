package com.kitex.mom.plan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 智能 APS 案件前端与内部只读视图。
 */
@Data
@Accessors(chain = true)
@Schema(description = "智能 APS 案件视图")
public class PlnApsCaseDTO {
    private String id;
    private String caseNo;
    private String caseType;
    private String title;
    private String caseStatus;
    private String currentStage;
    private String currentStep;
    private String ownerCode;
    private String ownerName;
    private Integer sourceVersion;
    private String previousCaseId;
    private String planningPolicyId;
    private Integer planningPolicyVersion;
    private String planningPolicyFingerprint;
    private String mrpAuthority;
    private String formalPlanContractId;
    private String formalPlanContractNo;
    private Integer formalPlanContractVersion;
    private String formalPlanContractFingerprint;
    private String resourceSnapshotId;
    private String resourceFingerprint;
    private String scenarioId;
    private String selectedTaskId;
    private String effectReviewId;
    private String dispatchStatus;
    private Integer workflowVersion;
    private String contextFingerprint;
    private String closeReason;
    private LocalDateTime closedTime;
    private String factoryCode;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private List<Source> sources;
    private List<Operation> recentOperations;

    /**
     * 案件来源引用，只返回服务端核验后的身份与版本。
     */
    @Data
    @Accessors(chain = true)
    public static class Source {
        private String id;
        private String sourceType;
        private String sourceId;
        private String sourceCode;
        private String sourceVersion;
        private String sourceFingerprint;
        private String sourceRole;
        private String relationshipStatus;
    }

    /**
     * 案件最近操作摘要，不下发内部前后JSON快照。
     */
    @Data
    @Accessors(chain = true)
    public static class Operation {
        private String id;
        private String requestId;
        private String action;
        private String beforeStatus;
        private String afterStatus;
        private String beforeStage;
        private String afterStage;
        private Integer expectedVersion;
        private Integer resultVersion;
        private String reason;
        private String operatorCode;
        private String operatorName;
        private LocalDateTime operationTime;
    }
}
