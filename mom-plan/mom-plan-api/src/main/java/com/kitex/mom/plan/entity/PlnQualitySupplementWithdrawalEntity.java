package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 质量补产下游受控撤回审批、分阶段执行和失败续跑主账。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_quality_supplement_withdrawal")
@EqualsAndHashCode(callSuper = true)
public class PlnQualitySupplementWithdrawalEntity extends BaseEntity<PlnQualitySupplementWithdrawalEntity> {
    private String withdrawalNo;
    private String submitRequestId;
    private String requestFingerprint;
    private Long supplementId;
    private String supplementNo;
    private Long productDemandId;
    private String productDemandNo;
    private Long supplementIssueId;
    private String supplementIssueNo;
    private String evidenceFingerprint;
    private String localEvidenceFingerprint;
    private String supplyEvidenceFingerprint;
    private String mesEvidenceFingerprint;
    private String supplyEvidenceSnapshot;
    private String evidenceSnapshot;
    private String reason;
    /** SUBMITTED、REJECTED、APPROVED、EXECUTING、PARTIAL、FAILED、SUCCEEDED、STALE。 */
    private String status;
    private String applicantCode;
    private String applicantName;
    private LocalDateTime submittedTime;
    private String reviewRequestId;
    private Boolean approved;
    private String reviewReason;
    private String reviewerCode;
    private String reviewerName;
    private LocalDateTime reviewedTime;
    private String executionRequestId;
    private Integer retryCount;
    private String supplyPhaseStatus;
    private String supplyPhaseMessage;
    private LocalDateTime supplyHandledTime;
    private String mesPhaseStatus;
    private String mesPhaseMessage;
    private LocalDateTime mesHandledTime;
    private String wmsPhaseStatus;
    private String wmsPhaseMessage;
    private LocalDateTime wmsHandledTime;
    private String apsPhaseStatus;
    private String apsPhaseMessage;
    private LocalDateTime apsHandledTime;
    private String planPhaseStatus;
    private String planPhaseMessage;
    private LocalDateTime planHandledTime;
    private String lastError;
    private String resultSnapshot;
    private LocalDateTime completedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
