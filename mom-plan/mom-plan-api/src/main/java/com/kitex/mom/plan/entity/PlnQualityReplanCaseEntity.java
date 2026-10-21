package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 质量偏差达到阈值后形成的排程处置案件。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_quality_replan_case")
@EqualsAndHashCode(callSuper = true)
public class PlnQualityReplanCaseEntity extends BaseEntity<PlnQualityReplanCaseEntity> {
    private String caseNo;
    private Long triggerEvaluationId;
    private Long latestEvaluationId;
    private Long issueId;
    private String issueNo;
    private String productCode;
    private String productName;
    private Long policyId;
    private String policyCode;
    private Integer policyVersion;
    private String policyFingerprint;
    private String formulaVersion;
    private BigDecimal plannedQuantity;
    private BigDecimal pendingDispositionQuantity;
    private BigDecimal openReworkQuantity;
    private BigDecimal irreversibleLossQuantity;
    private BigDecimal atRiskQuantity;
    private BigDecimal deviationRate;
    /** 报废导致的补产数量；局部重排不能替代补产闭环。 */
    private BigDecimal requiredSupplementQuantity;
    private String qualityMetricsSnapshot;
    private String qualityEvidenceFingerprint;
    /** 多候选中的派生案件指向质量事实根案件；根案件自身为空。 */
    private Long sourceCaseId;
    private Long scenarioId;
    private LocalDateTime firstTriggeredTime;
    private LocalDateTime latestTriggeredTime;
    /** PENDING_ASSESSMENT、SUPPLEMENT_REQUIRED、PROPOSED、SELECTED、SUPERSEDED、STALE、PUBLISHED、EXPIRED、CANCELLED、ROLLED_BACK、RECOVERED。 */
    private String status;
    private String assessRequestId;
    private String assessRequestSnapshot;
    private Long originalTaskId;
    private Long proposalTaskId;
    private LocalDateTime expectedRecoveryTime;
    private String affectedProcessCodes;
    private LocalDateTime freezeUntil;
    private Integer maxDelayMinutes;
    private Integer directAffectedCount;
    private Integer movedOperationCount;
    private Long maximumShiftMinutes;
    private LocalDateTime originalCompletionTime;
    private LocalDateTime proposedCompletionTime;
    private String deliveryRisk;
    private String mesExecutionFingerprint;
    private String originalScheduleFingerprint;
    private String originalScheduleSnapshot;
    private String proposedScheduleSnapshot;
    private String proposalFingerprint;
    private String reason;
    private String applicantCode;
    private String applicantName;
    private LocalDateTime proposedTime;
    private LocalDateTime expiresTime;
    private String publishRequestId;
    private String publisherCode;
    private String publisherName;
    private LocalDateTime publishedTime;
    private String mesPublishedFingerprint;
    private String cancelRequestId;
    private String cancelReason;
    private String cancelledBy;
    private String cancelledByName;
    private LocalDateTime cancelledTime;
    private String rollbackRequestId;
    private String rollbackReason;
    private String rolledBackBy;
    private String rolledBackByName;
    private LocalDateTime rolledBackTime;
    private String mesRollbackFingerprint;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @TableField(exist = false)
    private BigDecimal coveredSupplementQuantity;
    @TableField(exist = false)
    private BigDecimal pendingSupplementQuantity;
    @TableField(exist = false)
    private BigDecimal outstandingSupplementQuantity;
    @TableField(exist = false)
    private BigDecimal overCoveredSupplementQuantity;
    @TableField(exist = false)
    private String scenarioNo;
    @TableField(exist = false)
    private String candidateCode;
    @TableField(exist = false)
    private String candidateName;
    @TableField(exist = false)
    private BigDecimal candidateScore;
    @TableField(exist = false)
    private Integer candidateRank;
    @TableField(exist = false)
    private Boolean candidateFeasible;
    @TableField(exist = false)
    private Boolean selectedCandidate;
    @TableField(exist = false)
    private String candidateMetricsSnapshot;
}
