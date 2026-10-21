package com.kitex.mom.plan.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper=true) @TenantTable @TableName("pln_scheduling_lifecycle_operation")
public class PlnSchedulingLifecycleOperation extends BaseEntity<PlnSchedulingLifecycleOperation> {
    private Long taskId;
    private String scopeKey;
    private String requestId;
    private String commandFingerprint;
    private String action;
    private String reason;
    private String beforeSnapshot;
    private String afterSnapshot;
    private Long operatorId;
    private java.time.LocalDateTime operationTime;
    private String factoryCode;
}
