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

/** 紧急插单联合重排的版本化治理策略。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_insertion_replan_policy")
@EqualsAndHashCode(callSuper = true)
public class PlnInsertionReplanPolicyEntity extends BaseEntity<PlnInsertionReplanPolicyEntity> {
    private String policyCode;
    private String policyName;
    private Integer freezeMinutes;
    private Integer impactHorizonDays;
    private Integer maxAffectedIssues;
    private Integer maxMovedOperations;
    private Integer maxTotalDelayMinutes;
    private Integer maxSingleIssueDelayMinutes;
    private Integer maxInsertionLateMinutes;
    private Integer maxNewLateIssues;
    private Integer priority;
    private Integer policyVersion;
    private String policyFingerprint;
    /** DRAFT、ACTIVE、RETIRED。 */
    private String status;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private String authorCode;
    private String authorName;
    private String activationRequestId;
    private String approverCode;
    private String approverName;
    private LocalDateTime approvedTime;
    private String retireRequestId;
    private String retireReason;
    private String retiredBy;
    private LocalDateTime retiredTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
