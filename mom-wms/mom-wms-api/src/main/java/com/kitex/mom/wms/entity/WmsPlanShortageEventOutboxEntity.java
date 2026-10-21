package com.kitex.mom.wms.entity;

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
 * WMS已承诺供应失效后向PLAN投递缺料变化的本地事务Outbox。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_plan_shortage_event_outbox")
@EqualsAndHashCode(callSuper = true)
public class WmsPlanShortageEventOutboxEntity extends BaseEntity<WmsPlanShortageEventOutboxEntity> {
    private String eventId;
    private String eventType;
    private Integer eventVersion;
    private String sourceType;
    private Long sourceId;
    private String sourceNo;
    private Long allocationBillId;
    private String allocationBillNo;
    private Long varianceCaseId;
    private String varianceCaseNo;
    private Long mrpId;
    private String issueNo;
    private String materialCode;
    private BigDecimal shortageQuantity;
    private LocalDateTime occurredTime;
    private LocalDateTime expectedSupplyTime;
    private String forecastBasis;
    private String reason;
    private String sourcePayloadJson;
    private String sourceFingerprint;
    private String payloadJson;
    private String payloadFingerprint;
    /**
     * WAITING_DATA、PENDING、PROCESSING、RETRY_WAIT、DELIVERED、DEAD_LETTER。
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
    private String manualRequestId;
    private String manualReason;
    private String manualOperatorCode;
    private String manualOperatorName;
    private LocalDateTime manualOperationTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
