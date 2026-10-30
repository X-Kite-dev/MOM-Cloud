package com.kitex.mom.plan.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MES工单对应的PLAN资源异常影响与受控局部重排建议证据。
 *
 * <p>本契约只读取PLAN已经由确定性求解器生成的候选和审批状态。没有正式资源异常案件时，
 * 只能提示进入资源异常评估工作台，不能由大模型自行生成工序日期或资源分配。</p>
 */
public final class PlanResourceReplanAdvisoryDTO {

    private PlanResourceReplanAdvisoryDTO() {
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "工单资源异常影响与局部重排建议查询")
    public static class Query {
        @NotBlank
        @Size(max = 100)
        private String factoryCode;

        @NotBlank
        @Size(max = 100)
        private String taskCode;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "PLAN资源异常影响与受控局部重排建议证据")
    public static class Evidence {
        private String toolCode;
        private String metricDefinition;
        private String factoryCode;
        private String taskCode;
        private String mesMappingStatus;
        private Boolean mesMappingComplete;
        private String mesMappingFingerprint;
        private LocalDateTime observedAt;
        private Summary summary;
        private List<PlanAdvisory> plans = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
        private List<String> evidenceRefs = new ArrayList<>();
        private String evidenceFingerprint;
        private Boolean truncated;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "工单映射计划的资源异常建议汇总")
    public static class Summary {
        private Integer sourcePlanCount;
        private Integer planWithCaseCount;
        private Integer candidateCount;
        private Integer feasibleCandidateCount;
        private Integer awaitingA2Count;
        private Integer awaitingA3Count;
        private Integer publishedCount;
        private Integer reassessmentRequiredCount;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "单个来源计划的资源影响和受控下一步")
    public static class PlanAdvisory {
        private Long issueId;
        private String issueNo;
        private String productCode;
        private String productName;
        private String issueStatus;
        private Boolean lockStatus;
        private Long lockTaskId;
        /**
         * NO_CASE/A2_SELECTION_REQUIRED/A3_REVIEW_REQUIRED/PUBLISHED/REASSESS_REQUIRED/NO_FEASIBLE_CANDIDATE。
         */
        private String advisoryStatus;
        private String nextControlledAction;
        private Integer candidateCount;
        private Integer feasibleCandidateCount;
        private Long preferredCandidateCaseId;
        private String preferredCandidateCode;
        private List<ResourceReplanCandidate> candidates = new ArrayList<>();
        private List<String> dataGaps = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "PLAN确定性资源异常局部重排候选")
    public static class ResourceReplanCandidate {
        private Long caseId;
        private String caseNo;
        private String caseStatus;
        private Long scenarioId;
        private String scenarioNo;
        private String scenarioStatus;
        private String resourceType;
        private String resourceCode;
        private String resourceName;
        private String authoritySourceType;
        private String authoritySourceNo;
        private LocalDateTime eventStartTime;
        private LocalDateTime expectedRecoveryTime;
        private Boolean openEnded;
        private LocalDateTime proposedTime;
        private LocalDateTime expiresTime;
        private String candidateCode;
        private String candidateName;
        private BigDecimal candidateScore;
        private Integer candidateRank;
        private Boolean candidateFeasible;
        private Boolean selectedCandidate;
        private Integer directAffectedCount;
        private Integer movedOperationCount;
        private Integer reassignedOperationCount;
        private Long maximumShiftMinutes;
        private LocalDateTime originalCompletionTime;
        private LocalDateTime proposedCompletionTime;
        private String deliveryRisk;
        private String affectedProcessCodes;
        private String reason;
        private String resourceEvidenceFingerprint;
        private String proposalFingerprint;
        private String mesExecutionFingerprint;
        private List<OperationImpact> operationImpacts = new ArrayList<>();
        private Boolean operationImpactTruncated;
    }

    @Data
    @Accessors(chain = true)
    @Schema(description = "资源异常候选中的逐工序影响")
    public static class OperationImpact {
        private Long originalOperationId;
        private Long proposalOperationId;
        private Integer operationSeq;
        private String processCode;
        private String processName;
        private Boolean directAffected;
        private Boolean downstreamAffected;
        private String actionType;
        private String blockedReason;
        private LocalDateTime originalStartTime;
        private LocalDateTime originalEndTime;
        private LocalDateTime proposedStartTime;
        private LocalDateTime proposedEndTime;
        private String originalResourceCode;
        private String proposedResourceCode;
        private String originalDeviceCode;
        private String proposedDeviceCode;
        private String originalEmployeeCode;
        private String proposedEmployeeCode;
        private String originalToolingCodes;
        private String proposedToolingCodes;
        private Boolean resourceReassigned;
        private Long shiftMinutes;
    }
}
