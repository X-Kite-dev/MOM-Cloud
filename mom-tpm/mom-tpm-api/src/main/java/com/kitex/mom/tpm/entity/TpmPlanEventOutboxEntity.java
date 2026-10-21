package com.kitex.mom.tpm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * TPM权威设备事实向PLAN制造事件中心投递的本地事务Outbox。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("tpm_plan_event_outbox")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "TPM到PLAN的可靠制造事件投递箱")
public class TpmPlanEventOutboxEntity extends BaseEntity<TpmPlanEventOutboxEntity> {
    private String eventId;
    private String eventType;
    private Integer eventVersion;
    private String sourceType;
    private Long sourceId;
    private String sourceNo;
    private String deviceCode;
    private String deviceName;
    private LocalDateTime occurredTime;
    private LocalDateTime eventStartTime;
    private LocalDateTime expectedRecoveryTime;
    private String reason;
    private String recoveryForecastReason;
    private String payloadJson;
    private String payloadFingerprint;
    /**
     * WAITING_DATA、PENDING、PROCESSING、RETRY_WAIT、DELIVERED、DEAD_LETTER、CANCELLED。
     */
    private String status;
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
    private String sourceStatus;
    private LocalDateTime sourceRecoveredTime;
    private String manualRequestId;
    private String manualReason;
    private String manualOperatorCode;
    private String manualOperatorName;
    private LocalDateTime manualOperationTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
