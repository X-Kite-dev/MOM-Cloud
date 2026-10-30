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

/**
 * 可重放、可追责的制造异常事件收件箱。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_automation_event")
@EqualsAndHashCode(callSuper = true)
public class PlnAutomationEventEntity extends BaseEntity<PlnAutomationEventEntity> {
    private String eventNo;
    private String eventId;
    private String sourceSystem;
    private String eventType;
    private Integer eventVersion;
    private String aggregateType;
    private String aggregateId;
    private String requestFingerprint;
    private String payloadJson;
    private String payloadFingerprint;
    private LocalDateTime occurredTime;
    private LocalDateTime receivedTime;
    private Integer priority;
    private Long policyId;
    private String policyCode;
    private Integer policyVersion;
    private String policyFingerprint;
    private String handlerCode;
    private Boolean autoExecute;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime nextAttemptTime;
    private LocalDateTime lastStartedTime;
    private LocalDateTime completedTime;
    private String lastErrorCode;
    private String lastErrorMessage;
    private String resultSummary;
    private String targetType;
    private Long targetId;
    private String targetNo;
    private String manualRequestId;
    private String manualReason;
    private String manualOperatorCode;
    private String manualOperatorName;
    private LocalDateTime manualOperationTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
