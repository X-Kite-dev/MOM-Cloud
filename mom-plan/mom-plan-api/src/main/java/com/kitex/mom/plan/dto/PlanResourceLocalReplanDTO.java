package com.kitex.mom.plan.dto;

import com.kitex.mom.plan.entity.PlnResourceReplanCaseEntity;
import com.kitex.mom.plan.entity.PlnResourceReplanLineEntity;
import com.kitex.mom.plan.entity.PlnResourceReplanOperationEntity;
import com.kitex.mom.plan.entity.PlnSchedulingScenarioEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 资源异常局部重排与维修恢复复核接口模型。
 *
 * <p>资源证据同时包含 PLAN、MES 与 TPM 事实；维修恢复后必须通过复核或重新评估，不能直接恢复旧方案。</p>
 */
public final class PlanResourceLocalReplanDTO {

    private PlanResourceLocalReplanDTO() {
    }

    @Data
    @Schema(description = "资源异常重排策略查询条件")
    public static class PolicyQuery {
        private String policyCode;
        private String resourceType;
        private String resourceCode;
        private String status;
    }

    @Data
    @Schema(description = "资源异常重排策略草稿命令")
    public static class PolicyDraftCommand {
        private Long id;
        private String policyCode;
        private String policyName;
        private String factoryCode;
        private String resourceType;
        private String resourceCode;
        private Integer freezeMinutes;
        private Integer maxDelayMinutes;
        private Integer priority;
        private LocalDateTime effectiveFrom;
        private LocalDateTime effectiveTo;
    }

    @Data
    @Schema(description = "资源异常重排策略状态变更命令")
    public static class PolicyActionCommand {
        private Long policyId;
        private String requestId;
        private String reason;
    }

    @Data
    @Schema(description = "资源异常重排方案查询条件")
    public static class CaseQuery {
        private String caseNo;
        private String issueNo;
        private String resourceType;
        private String resourceCode;
        private String status;
        private String recoveryReviewStatus;
    }

    @Data
    @Schema(description = "资源异常影响评估命令")
    public static class AssessCommand {
        private Long issueId;
        private String requestId;
        private String resourceType;
        private String resourceCode;
        private LocalDateTime eventStartTime;
        private LocalDateTime expectedRecoveryTime;
        private Boolean openEnded;
        private String reason;
    }

    @Data
    @Schema(description = "MES执行偏差局部重排评估命令")
    public static class ExecutionDeviationAssessCommand {
        @Schema(description = "发生执行偏差的已发布排程情景ID")
        private Long sourceScenarioId;
        @Schema(description = "本次只处理的一张生产计划ID；多计划偏差须分别形成案件")
        private Long issueId;
        @Schema(description = "永久幂等请求号")
        private String requestId;
        @Schema(description = "调用方已复核的排程效果指纹")
        private String expectedEffectFingerprint;
        @Schema(description = "可选的直接偏差工序编码；为空时处理该计划全部可重排偏差")
        private List<String> processCodes;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Schema(description = "原冻结路线节点选择；旧主工序选择仅可唯一对应原工序")
        private List<String> sourceRouteNodeCodes;
        @Schema(description = "人工评估原因")
        private String reason;
    }

    @Data
    @Schema(description = "维修恢复后的候选重新优化命令")
    public static class RecoveryOptimizeCommand {
        private Long recoveryReviewCaseId;
        private String requestId;
        private String reason;
    }

    @Data
    @Schema(description = "资源异常重排正式发布命令")
    public static class PublishCommand {
        private Long caseId;
        private String requestId;
        private String expectedProposalFingerprint;
        private String expectedResourceEvidenceFingerprint;
        private String expectedMesExecutionFingerprint;
    }

    @Data
    @Schema(description = "资源异常重排取消命令")
    public static class CancelCommand {
        private Long caseId;
        private String requestId;
        private String reason;
    }

    @Data
    @Schema(description = "资源异常重排受控回退命令")
    public static class RollbackCommand {
        private Long caseId;
        private String requestId;
        private String expectedProposalFingerprint;
        private String expectedMesExecutionFingerprint;
        private String reason;
    }

    @Data
    @Schema(description = "资源异常重排候选选择命令")
    public static class SelectCommand {
        private String requestId;
        private Long candidateCaseId;
        private String expectedSourceFingerprint;
        private String reason;
    }

    @Data
    @Schema(description = "资源或执行偏差局部重排情景查询命令")
    public static class ScenarioQueryCommand {
        @Schema(description = "局部重排情景ID")
        private Long scenarioId;
    }

    @Data
    @Schema(description = "维修恢复人工复核命令")
    public static class RecoveryReviewCommand {
        private Long caseId;
        private Long recoveryReviewEventId;
        private String requestId;
        @Schema(description = "复核结论", allowableValues = {"KEEP_PUBLISHED", "REQUIRE_REPLAN", "CONFIRM_REPLAN"})
        private String decision;
        private String expectedEvidenceFingerprint;
        private String reason;
        private Long replacementTaskId;
        private Long replacementCaseId;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "维修恢复人工复核预览")
    public static class RecoveryReviewPreview {
        private Long caseId;
        private Long recoveryReviewEventId;
        private Long recoveryReceiptId;
        private String reviewStatus;
        private String issueNo;
        private String deviceCode;
        private Long currentTaskId;
        private Long originalPublishedTaskId;
        private String currentScheduleFingerprint;
        private Boolean currentTaskOfficial;
        private Boolean mesTaskPresent;
        private Boolean deviceCurrentlyUsable;
        private Boolean tpmCurrentlyClear;
        private String mesExecutionFingerprint;
        private String evidenceFingerprint;
        private List<String> gaps;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @Schema(description = "资源异常局部重排方案详情")
    public static class CaseDetail extends PlnResourceReplanCaseEntity {
        private List<PlnResourceReplanLineEntity> lines;
        private List<PlnResourceReplanOperationEntity> operations;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "资源异常局部重排情景及候选")
    public static class Scenario {
        private PlnSchedulingScenarioEntity scenario;
        private List<CaseDetail> candidates;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "资源异常局部重排发布或回退回执")
    public static class PublishReceipt {
        private Long caseId;
        private String caseNo;
        private String issueNo;
        private String status;
        private Integer movedOperationCount;
        private Integer reassignedOperationCount;
        private LocalDateTime newCompletionTime;
        private String mesAfterExecutionFingerprint;
    }
}
