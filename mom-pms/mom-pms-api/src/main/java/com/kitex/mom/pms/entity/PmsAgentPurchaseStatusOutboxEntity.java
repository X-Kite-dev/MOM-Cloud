package com.kitex.mom.pms.entity;

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
 * Agent采购订单终态可靠回写PLAN的本地事务Outbox。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pms_agent_purchase_status_outbox")
@EqualsAndHashCode(callSuper = true)
public class PmsAgentPurchaseStatusOutboxEntity extends BaseEntity<PmsAgentPurchaseStatusOutboxEntity> {
    private String eventId;
    private String eventType;
    private String sourceFingerprint;
    private String agentRequestId;
    private Long mrpId;
    private Long pmsOrderId;
    private String pmsOrderNo;
    private Long pmsOrderDetailId;
    private String pmsPlanNo;
    private String orderStatus;
    private String reason;
    private LocalDateTime occurredTime;
    /**
     * PENDING / PROCESSING / RETRY_WAIT / DELIVERED / DEAD_LETTER / SOURCE_CLOSED。
     */
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime nextAttemptTime;
    private LocalDateTime lastAttemptTime;
    private LocalDateTime deliveredTime;
    private String planResultStatus;
    private LocalDateTime planHandledTime;
    private String planMessage;
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
