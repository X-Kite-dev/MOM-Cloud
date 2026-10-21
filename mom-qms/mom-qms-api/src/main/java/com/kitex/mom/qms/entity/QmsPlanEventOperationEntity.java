package com.kitex.mom.qms.entity;

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
 * QMS质量事实Outbox每次状态迁移与人工处置的不可变履历。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("qms_plan_event_operation")
@EqualsAndHashCode(callSuper = true)
public class QmsPlanEventOperationEntity extends BaseEntity<QmsPlanEventOperationEntity> {
    private Long outboxId;
    private Long targetId;
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
