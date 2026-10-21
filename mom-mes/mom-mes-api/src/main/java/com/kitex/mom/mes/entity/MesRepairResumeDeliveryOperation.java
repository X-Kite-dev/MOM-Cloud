package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * MES维修真实复工跨域投递的不可变尝试与人工续投履历。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mes_repair_resume_delivery_operation")
public class MesRepairResumeDeliveryOperation extends BaseEntity<MesRepairResumeDeliveryOperation> {
    private String factoryCode;
    private Long outboxId;
    private String action;
    private Integer attemptNo;
    private String beforeStatus;
    private String afterStatus;
    private String requestId;
    private String detail;
    private LocalDateTime operationTime;
}
