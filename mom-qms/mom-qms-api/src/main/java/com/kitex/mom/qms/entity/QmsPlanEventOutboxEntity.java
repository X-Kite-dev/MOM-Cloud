package com.kitex.mom.qms.entity;

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
 * QMS生产质量处置事实向MES和PLAN可靠投递的本地事务Outbox。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("qms_plan_event_outbox")
@EqualsAndHashCode(callSuper = true)
public class QmsPlanEventOutboxEntity extends BaseEntity<QmsPlanEventOutboxEntity> {
    private String eventId;
    private String eventType;
    private Integer eventVersion;
    private String sourceType;
    private Long sourceId;
    private String sourceNo;
    private String inspectionType;
    private Long inspectionTaskId;
    private Long handleRecordId;
    private String mesTaskCode;
    private String mesProduceTaskCode;
    private String dispositionKind;
    private BigDecimal quantity;
    private String reason;
    private LocalDateTime occurredTime;
    private String sourcePayloadJson;
    private String sourceFingerprint;
    private String mesRequestId;
    /**
     * PENDING、SYNCED。
     */
    private String mesSyncStatus;
    private LocalDateTime mesSyncedTime;
    private String mappingStatus;
    private String mappingFingerprint;
    private String mappingSnapshotJson;
    /**
     * PENDING、PROCESSING、RETRY_WAIT、WAITING_DATA、DELIVERED、DEAD_LETTER。
     */
    private String status;
    private Integer targetCount;
    private Integer deliveredTargetCount;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime nextAttemptTime;
    private LocalDateTime lastAttemptTime;
    private LocalDateTime deliveredTime;
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
