package com.kitex.mom.pms.entity;

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
 * 供应商正式交期承诺变化到PLAN制造事件中心的本地事务Outbox。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pms_plan_commitment_event_outbox")
@EqualsAndHashCode(callSuper = true)
public class PmsPlanCommitmentEventOutboxEntity extends BaseEntity<PmsPlanCommitmentEventOutboxEntity> {
    private String eventId;
    private String eventType;
    private Integer eventVersion;
    /**
     * PMS_DELIVERY_COMMITMENT_ACTIVATED / PMS_DELIVERY_COMMITMENT_CANCELLED / PMS_ORDER_REMAINING_CANCELLED。
     */
    private String sourceType;
    private Long commitmentId;
    private String commitmentNo;
    private Integer commitmentVersion;
    private Long orderChangeRequestId;
    private Long pmsOrderId;
    private Long orderDetailId;
    private String pmsOrderNo;
    private String pmsPlanNo;
    private String planDemandNo;
    private String materialCode;
    private BigDecimal openOrderedQuantity;
    private BigDecimal confirmedOpenQuantity;
    private BigDecimal affectedQuantity;
    /**
     * FULL / PARTIAL / NONE / RECEIVED。
     */
    private String coverageStatus;
    private LocalDateTime occurredTime;
    private LocalDateTime expectedSupplyTime;
    private String forecastBasis;
    private String reason;
    private String sourcePayloadJson;
    private String sourceFingerprint;
    private String payloadJson;
    private String payloadFingerprint;
    /**
     * WAITING_DATA/PENDING/PROCESSING/RETRY_WAIT/DELIVERED/DEAD_LETTER/SOURCE_CLOSED。
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
