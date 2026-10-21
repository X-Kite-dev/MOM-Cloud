package com.kitex.mom.plan.dto;

import com.kitex.mom.plan.entity.PlnQualityDeviationEvaluationEntity;
import com.kitex.mom.plan.entity.PlnQualityReplanCaseEntity;
import com.kitex.mom.plan.entity.PlnQualityReplanLineEntity;
import com.kitex.mom.plan.entity.PlnQualitySupplementDemandEntity;
import com.kitex.mom.plan.entity.PlnSchedulingScenarioEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 质量偏差、补产需求与局部重排接口模型。
 *
 * <p>质量证据指纹固化报工、检验和偏差评估事实；发布前必须重新校验，禁止使用过期质量结论改写排程。</p>
 */
public final class PlanQualityLocalReplanDTO {

    private PlanQualityLocalReplanDTO() {
    }

    @Data
    @Schema(description = "质量局部重排策略草稿命令")
    public static class PolicyDraftCommand {
        private Long id;
        private String policyCode;
        private String policyName;
        private String factoryCode;
        private String productCode;
        private String thresholdMode;
        private BigDecimal minAtRiskQuantity;
        private BigDecimal minDeviationRate;
        private BigDecimal minScrapQuantity;
        private Integer freezeMinutes;
        private Integer maxDelayMinutes;
        private Integer priority;
        private LocalDateTime effectiveFrom;
        private LocalDateTime effectiveTo;
    }

    @Data
    @Schema(description = "质量局部重排策略查询条件")
    public static class PolicyQuery {
        private String policyCode;
        private String productCode;
        private String status;
    }

    @Data
    @Schema(description = "质量局部重排策略状态变更命令")
    public static class PolicyActionCommand {
        private Long policyId;
        private String requestId;
        private String reason;
    }

    @Data
    @Schema(description = "质量偏差评估查询条件")
    public static class EvaluationQuery {
        private String issueNo;
        private String productCode;
        private String evaluationResult;
    }

    @Data
    @Schema(description = "质量偏差重新评估命令")
    public static class ReevaluateCommand {
        private Long issueId;
        private String requestId;
    }

    @Data
    @Schema(description = "质量局部重排方案查询条件")
    public static class CaseQuery {
        private String caseNo;
        private String issueNo;
        private String productCode;
        private String status;
    }

    @Data
    @Schema(description = "质量补产需求查询条件")
    public static class SupplementQuery {
        private String supplementNo;
        private String sourceIssueNo;
        private String productCode;
        private String status;
    }

    @Data
    @Schema(description = "质量补产需求提交命令")
    public static class SupplementSubmitCommand {
        private Long caseId;
        private String requestId;
        private BigDecimal requestQuantity;
        private LocalDate demandDate;
        private String demandWarehouseCode;
        private String expectedQualityEvidenceFingerprint;
        private String reason;
    }

    @Data
    @Schema(description = "质量补产需求审核命令")
    public static class SupplementReviewCommand {
        private Long supplementId;
        private String requestId;
        private Boolean approved;
        private String expectedRequestFingerprint;
        private String reason;
    }

    @Data
    @Schema(description = "质量补产需求取消命令")
    public static class SupplementCancelCommand {
        private Long supplementId;
        private String requestId;
        private String reason;
    }

    @Data
    @Schema(description = "已批准补产需求撤回申请命令")
    public static class SupplementWithdrawalSubmitCommand {
        private Long supplementId;
        private String requestId;
        private String reason;
    }

    @Data
    @Schema(description = "补产需求撤回审核命令")
    public static class SupplementWithdrawalReviewCommand {
        private Long withdrawalId;
        private String requestId;
        private Boolean approved;
        private String expectedEvidenceFingerprint;
        private String reason;
    }

    @Data
    @Schema(description = "补产需求撤回同步重试命令")
    public static class SupplementWithdrawalRetryCommand {
        private Long withdrawalId;
        private String requestId;
        private String reason;
    }

    @Data
    @Schema(description = "质量异常局部重排评估命令")
    public static class AssessCommand {
        private Long caseId;
        private String requestId;
        private LocalDateTime expectedRecoveryTime;
        private List<String> affectedProcessCodes;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        @Schema(description = "原冻结路线节点选择；旧主工序选择仅可唯一对应原工序")
        private List<String> affectedSourceRouteNodeCodes;
        private String expectedQualityEvidenceFingerprint;
        private String reason;
    }

    @Data
    @Schema(description = "质量异常局部重排正式发布命令")
    public static class PublishCommand {
        private Long caseId;
        private String requestId;
        private String expectedProposalFingerprint;
        private String expectedQualityEvidenceFingerprint;
        private String expectedMesExecutionFingerprint;
    }

    @Data
    @Schema(description = "质量异常局部重排取消命令")
    public static class CancelCommand {
        private Long caseId;
        private String requestId;
        private String reason;
    }

    @Data
    @Schema(description = "质量异常局部重排受控回退命令")
    public static class RollbackCommand {
        private Long caseId;
        private String requestId;
        private String expectedProposalFingerprint;
        private String expectedMesExecutionFingerprint;
        private String reason;
    }

    @Data
    @Schema(description = "质量异常局部重排候选选择命令")
    public static class SelectCommand {
        private String requestId;
        private Long candidateCaseId;
        private String expectedSourceFingerprint;
        private String reason;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @Schema(description = "质量异常局部重排方案详情")
    public static class CaseDetail extends PlnQualityReplanCaseEntity {
        private List<PlnQualityReplanLineEntity> lines;
        private List<PlnQualityDeviationEvaluationEntity> evaluations;
        private List<PlnQualitySupplementDemandEntity> supplements;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "质量异常局部重排情景及候选")
    public static class Scenario {
        private PlnSchedulingScenarioEntity scenario;
        private Long sourceCaseId;
        private List<CaseDetail> candidates;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "质量异常局部重排发布或回退回执")
    public static class PublishReceipt {
        private Long caseId;
        private String caseNo;
        private String issueNo;
        private String status;
        private Integer movedOperationCount;
        private BigDecimal requiredSupplementQuantity;
        private LocalDateTime newCompletionTime;
        private String mesAfterExecutionFingerprint;
    }
}
