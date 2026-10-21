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
 * Agent采购状态Outbox不可变操作履历。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pms_agent_purchase_status_operation")
@EqualsAndHashCode(callSuper = true)
public class PmsAgentPurchaseStatusOperationEntity extends BaseEntity<PmsAgentPurchaseStatusOperationEntity> {
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
