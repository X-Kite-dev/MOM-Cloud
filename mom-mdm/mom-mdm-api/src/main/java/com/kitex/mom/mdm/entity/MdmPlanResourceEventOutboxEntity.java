package com.kitex.mom.mdm.entity;

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
 * MDM工装、人员权威不可用事实向PLAN投递的本地事务Outbox。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mdm_plan_resource_event_outbox")
@EqualsAndHashCode(callSuper = true)
public class MdmPlanResourceEventOutboxEntity extends BaseEntity<MdmPlanResourceEventOutboxEntity> {
    private String eventId;
    private String eventType;
    private Integer eventVersion;
    private String sourceType;
    private Long sourceId;
    private String sourceNo;
    private String resourceType;
    private String resourceCode;
    private String resourceName;
    private LocalDateTime occurredTime;
    private LocalDateTime eventStartTime;
    private LocalDateTime expectedRecoveryTime;
    /**
     * true表示报废、停用、资格失效等尚无确定恢复时刻的开放式不可用事实。
     */
    private Boolean openEnded;
    private String reason;
    private String sourcePayloadJson;
    private String sourceFingerprint;
    private String payloadJson;
    private String payloadFingerprint;
    /**
     * PENDING、PROCESSING、RETRY_WAIT、DELIVERED、DEAD_LETTER、CANCELLED。
     */
    private String status;
    /**
     * ACTIVE、CANCELLED。
     */
    private String sourceStatus;
    private LocalDateTime sourceClosedTime;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime nextAttemptTime;
    private LocalDateTime lastAttemptTime;
    private LocalDateTime deliveredTime;
    private Long planInboxId;
    private String planEventNo;
    private String planEventStatus;
    private String lastErrorCode;
    private String lastErrorMessage;
    private String manualRequestId;
    private String manualReason;
    private String manualOperatorCode;
    private String manualOperatorName;
    private LocalDateTime manualOperationTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
