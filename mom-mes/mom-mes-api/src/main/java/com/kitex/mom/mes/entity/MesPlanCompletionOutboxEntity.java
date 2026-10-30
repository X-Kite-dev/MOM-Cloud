package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 每工单完成世代一份不可变正向通知；只有投递状态可变。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_plan_completion_outbox")
@EqualsAndHashCode(callSuper = true)
public class MesPlanCompletionOutboxEntity extends BaseEntity<MesPlanCompletionOutboxEntity> {
    private String eventId;
    private Long taskId;
    private String taskCode;
    private Integer completionVersion;
    private String payloadJson;
    private String payloadFingerprint;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptTime;
    private LocalDateTime deliveredTime;
    private String planReceiptIdsJson;
    private String lastError;
    private String factoryCode;
}
