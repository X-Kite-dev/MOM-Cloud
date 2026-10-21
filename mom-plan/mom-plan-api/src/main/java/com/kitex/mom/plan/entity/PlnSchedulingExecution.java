package com.kitex.mom.plan.entity;
import com.baomidou.mybatisplus.annotation.*;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
@Data @EqualsAndHashCode(callSuper=true) @TenantTable @TableName("pln_scheduling_execution")
public class PlnSchedulingExecution extends BaseEntity<PlnSchedulingExecution> {
    private String requestId;
    private String commandFingerprint;
    private String reason;
    private Long applicantId;
    private Long rootExecutionId;
    private Long parentExecutionId;
    private Integer versionNo;
    private String inputJson;
    private String inputFingerprint;
    private String status;
    private Integer timeoutSeconds;
    private Integer attemptCount;
    private Boolean cancelRequested;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private String leaseToken;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private LocalDateTime leaseUntil;
    private LocalDateTime deadline;
    private LocalDateTime startedTime;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private LocalDateTime finishedTime;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private String lastError;
    private Long resultTaskId;
    private String warning;
    private String factoryCode;
}
