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
 * 一张紧急计划与被挤占计划共同形成的联合局部重排案件。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_insertion_replan_case")
@EqualsAndHashCode(callSuper = true)
public class PlnInsertionReplanCaseEntity extends BaseEntity<PlnInsertionReplanCaseEntity> {
    private String caseNo;
    private Long scenarioId;
    private String assessRequestId;
    private String assessRequestFingerprint;
    private Long insertionIssueId;
    private String insertionIssueNo;
    private String productCode;
    private String productName;
    private String urgencyLevel;
    private LocalDateTime requestedStartTime;
    private String businessReason;
    private Long policyId;
    private String policyCode;
    private Integer policyVersion;
    private String policyFingerprint;
    private LocalDateTime freezeUntil;
    private LocalDateTime impactHorizonEnd;
    private Integer maxAffectedIssues;
    private Integer maxMovedOperations;
    private Integer maxTotalDelayMinutes;
    private Integer maxSingleIssueDelayMinutes;
    private Integer maxInsertionLateMinutes;
    private Integer maxNewLateIssues;
    private Long proposalTaskId;
    private Long publishedTaskId;
    private Integer affectedIssueCount;
    private Integer movedIssueCount;
    private Integer movedOperationCount;
    private Integer reassignedOperationCount;
    private Integer newLateIssueCount;
    private Long totalDelayMinutes;
    private Long maximumDelayMinutes;
    private Long insertionLateMinutes;
    private LocalDateTime insertionCompletionTime;
    private String decisionResult;
    private String sourceIssueFingerprint;
    private String materialEvidenceSnapshot;
    private String materialEvidenceFingerprint;
    private String mesExecutionSnapshot;
    private String mesExecutionFingerprint;
    private String originalScheduleSnapshot;
    private String originalScheduleFingerprint;
    private String proposedScheduleSnapshot;
    private String proposalFingerprint;
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
    private String mesPublishedSnapshot;
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
    private String mesRollbackSnapshot;
    private String mesRollbackFingerprint;
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
