package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.util.TenantTable;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 复检累计结果的冻结发送原件；进度可更新，身份和原文不可改写。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName(value = "qms_rework_result_outbox", schema = "mom_qms")
public class QmsReworkResultOutboxEntity {
    private Long id;
    private Long tenantId;
    private String factoryCode;
    private Long orderId;
    private Long sequenceNo;
    private String payload;
    private String fingerprint;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptTime;
    private LocalDateTime createdAt;
    private LocalDateTime deliveredAt;
    private String lastError;
}
