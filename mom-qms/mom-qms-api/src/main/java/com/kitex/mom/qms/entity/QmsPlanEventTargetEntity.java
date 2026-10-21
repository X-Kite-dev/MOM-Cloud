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
 * 一笔QMS事实按MES拆合批来源账展开后的逐PLAN计划投递目标。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("qms_plan_event_target")
@EqualsAndHashCode(callSuper = true)
public class QmsPlanEventTargetEntity extends BaseEntity<QmsPlanEventTargetEntity> {
    private Long outboxId;
    private String eventId;
    private Long issueId;
    private String issueNo;
    /**
     * 本次QMS事实经MES实际归集给该PLAN计划的数量增量，不是整张工单的生产分配量。
     */
    private BigDecimal allocatedQuantity;
    private String mappingFingerprint;
    private String payloadJson;
    private String payloadFingerprint;
    /**
     * PENDING、DELIVERED。失败随Outbox统一退避并安全重放。
     */
    private String status;
    private Integer attemptCount;
    private LocalDateTime lastAttemptTime;
    private LocalDateTime deliveredTime;
    private Long planInboxId;
    private String planEventNo;
    private String planEventStatus;
    private String lastErrorCode;
    private String lastErrorMessage;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
