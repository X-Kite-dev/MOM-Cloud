package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * MES真实维修复工切段同事务产生的PLAN执行事实Outbox。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mes_repair_resume_outbox")
public class MesRepairResumeOutbox extends BaseEntity<MesRepairResumeOutbox> {
    private String factoryCode;
    private String eventId;
    private Long stopId;
    private Long releaseId;
    private Long arrangeId;
    private Long segmentId;
    private Long recoveryReceiptId;
    private String planNo;
    private String routeCode;
    private String deviceCode;
    private LocalDateTime resumedTime;
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
}
