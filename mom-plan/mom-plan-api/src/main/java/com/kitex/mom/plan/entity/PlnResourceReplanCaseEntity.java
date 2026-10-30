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

/**
 * 权威资源异常对一张正式生产计划形成的局部重排案件。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_resource_replan_case")
@EqualsAndHashCode(callSuper = true)
public class PlnResourceReplanCaseEntity extends BaseEntity<PlnResourceReplanCaseEntity> {
    private String caseNo;
    private String assessRequestId;
    private String assessRequestFingerprint;
    private Long issueId;
    private String issueNo;
    private String productCode;
    private String productName;
    private String resourceType;
    private String resourceCode;
    private String resourceName;
    private String evidenceSourceType;
    private String evidenceSourceNo;
    /**
     * 恢复优化候选所依据的已发布资源异常案件及MDM资格恢复回执。
     */
    private Long recoverySourceCaseId;
    private Long recoverySourceReceiptId;
    private LocalDateTime eventStartTime;
    private LocalDateTime expectedRecoveryTime;
    private Boolean openEnded;
    private String resourceEvidenceSnapshot;
    private String resourceEvidenceFingerprint;
    private Long policyId;
    private String policyCode;
    private Integer policyVersion;
    private String policyFingerprint;
    private Long originalTaskId;
    private Long proposalTaskId;
    private Long scenarioId;
    private String affectedProcessCodes;
    private LocalDateTime freezeUntil;
    private Integer maxDelayMinutes;
    private Integer directAffectedCount;
    private Integer movedOperationCount;
    private Integer reassignedOperationCount;
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
    /**
     * PROPOSED、SELECTED、SUPERSEDED、STALE、PUBLISHED、EXPIRED、CANCELLED、ROLLED_BACK。
     */
    private String status;
    private String publishRequestId;
    private String publisherCode;
    private String publisherName;
    private LocalDateTime publishedTime;
    private String mesPublishedFingerprint;
    /**
     * A3发布后真正锁定在原正式任务上的工序指纹；旧案为空时不允许凭空确认原排程未漂移。
     */
    private String publishedScheduleFingerprint;
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
    /**
     * 最新一次MDM设备资格恢复对已发布排程的人工复核状态；历史周期保留在不可变操作账。
     */
    private Long recoveryReviewEventId;
    private Long recoveryReceiptId;
    private String recoveryReviewStatus;
    private LocalDateTime recoveryReviewTime;
    private String recoveryReviewRequestId;
    private String recoveryReviewReason;
    private String recoveryReviewedBy;
    private LocalDateTime recoveryReviewedTime;
    private String recoveryReviewEvidenceFingerprint;
    private Long recoveryReplacementTaskId;
    private Long recoveryReplacementCaseId;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

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
