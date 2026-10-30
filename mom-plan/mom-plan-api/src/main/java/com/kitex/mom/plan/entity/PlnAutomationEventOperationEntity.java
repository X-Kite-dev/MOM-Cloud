package com.kitex.mom.plan.entity;

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
 * 事件接收、路由、执行、失败和人工接管的不可变履历。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_automation_event_operation")
@EqualsAndHashCode(callSuper = true)
public class PlnAutomationEventOperationEntity extends BaseEntity<PlnAutomationEventOperationEntity> {
    private Long eventId;
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
