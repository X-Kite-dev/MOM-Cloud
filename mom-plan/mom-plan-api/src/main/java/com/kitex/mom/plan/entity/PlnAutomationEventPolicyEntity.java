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

/** 制造异常事件到评估/排产场景的版本化订阅策略。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_automation_event_policy")
@EqualsAndHashCode(callSuper = true)
public class PlnAutomationEventPolicyEntity extends BaseEntity<PlnAutomationEventPolicyEntity> {
    private String policyCode;
    private String policyName;
    private String eventType;
    private String handlerCode;
    private Boolean autoExecute;
    private Integer maxAttempts;
    private Integer initialBackoffSeconds;
    private Integer maxBackoffSeconds;
    private Integer priority;
    private Integer policyVersion;
    private String policyFingerprint;
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
