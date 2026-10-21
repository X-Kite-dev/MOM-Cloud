package com.kitex.mom.tpm.entity;

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
 * Outbox每次状态迁移和人工处置的不可变审计账。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("tpm_plan_event_delivery_operation")
@EqualsAndHashCode(callSuper = true)
public class TpmPlanEventDeliveryOperationEntity extends BaseEntity<TpmPlanEventDeliveryOperationEntity> {
    private Long outboxId;
    private String requestId;
    private String operationType;
    private String beforeStatus;
    private String afterStatus;
    private Integer attemptNo;
    private String content;
    private String operatorCode;
    private String operatorName;
    private LocalDateTime operationTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
