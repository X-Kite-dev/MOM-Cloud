package com.kitex.mom.wms.entity;

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
 * WMS缺料事件预测补录、投递和人工重试的不可变履历。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_plan_shortage_event_operation")
@EqualsAndHashCode(callSuper = true)
public class WmsPlanShortageEventOperationEntity extends BaseEntity<WmsPlanShortageEventOperationEntity> {
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
