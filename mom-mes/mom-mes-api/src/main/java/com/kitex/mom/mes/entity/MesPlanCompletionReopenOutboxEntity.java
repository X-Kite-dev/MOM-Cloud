package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MES工单完工状态重新打开后可靠投递PLAN的Outbox。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_plan_completion_reopen_outbox")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "MES完工重新打开PLAN回写Outbox")
public class MesPlanCompletionReopenOutboxEntity extends BaseEntity<MesPlanCompletionReopenOutboxEntity> {

    private String eventId;
    private Long reverseRequestId;
    private Long taskId;
    private String taskCode;
    private String planNo;
    private String apsBatchNo;
    private String productCode;
    private BigDecimal completeQuantityBefore;
    private BigDecimal completeQuantityAfter;
    private Integer completionVersionBefore;
    private Integer completionVersionAfter;
    private LocalDateTime reopenedTime;
    private String payloadJson;
    private String payloadFingerprint;
    /**
     * PENDING/RETRY_WAIT/DELIVERED/DEAD_LETTER。
     */
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptTime;
    private LocalDateTime deliveredTime;
    private Long planInboxId;
    private String planEventNo;
    private String planEventStatus;
    private String lastError;
    private String retryRequestId;
    private String factoryCode;
}
