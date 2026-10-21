package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** MES维修后实际复工的PLAN观察账；不替代MES批准或PLAN复核决定。 */
@Data @EqualsAndHashCode(callSuper = true) @TenantTable @TableName("pln_repair_resume_observation")
public class PlnRepairResumeObservation extends BaseEntity<PlnRepairResumeObservation> {
    private String factoryCode;
    private Long inboxId;
    private Long mesOutboxId;
    private Long stopId;
    private Long releaseId;
    private Long arrangeId;
    private Long segmentId;
    private Long recoveryReceiptId;
    private Long issueId;
    private Long resourceCaseId;
    private String planNo;
    private String routeCode;
    private String deviceCode;
    private LocalDateTime resumedTime;
    private String sourceFingerprint;
    /** MATCHED / NO_PLAN / NO_RECOVERY_CASE / CASE_PENDING / CASE_RESOLVED。 */
    private String matchStatus;
    private String planScheduleFingerprint;
    private String mesExecutionFingerprint;
    private LocalDateTime observedTime;
}
