package com.kitex.mom.plan.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
@Data @EqualsAndHashCode(callSuper=true) @TenantTable @TableName("pln_scheduling_execution_operation")
public class PlnSchedulingExecutionOperation extends BaseEntity<PlnSchedulingExecutionOperation> {
    private Long executionId;
    private String requestId;
    private String commandFingerprint;
    private String action;
    private String reason;
    private Long operatorId;
    private Integer attemptNo;
    private String leaseToken;
    private String resultStatus;
    private Long resultTaskId;
    private java.time.LocalDateTime operationTime;
    private String factoryCode;
}
