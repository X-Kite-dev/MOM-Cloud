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

@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_scheduling_batch_change")
@EqualsAndHashCode(callSuper = true)
public class PlnSchedulingBatchChangeEntity extends BaseEntity<PlnSchedulingBatchChangeEntity> {
    private Long taskId;
    private String requestId;
    private String commandHash;
    private String beforeQuantityFingerprint;
    private String afterQuantityFingerprint;
    private String beforeSnapshot;
    private String afterSnapshot;
    private String reason;
    private String changeStatus;
    private LocalDateTime changedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
