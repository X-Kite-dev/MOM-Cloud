package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 设备/工装换型开始到完成的权威执行事实。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_changeover_execution")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "换型执行时间账")
public class MesChangeoverExecutionEntity extends BaseEntity<MesChangeoverExecutionEntity> {
    private String requestId;
    @Schema(description = "STARTED/COMPLETED/CANCELLED")
    private String status;
    private String worksiteCode;
    private String deviceCode;
    private String deviceName;
    private String fromToolingCode;
    private String fromToolingName;
    private String toToolingCode;
    private String toToolingName;
    private LocalDateTime startedTime;
    private String startedBy;
    private String startedByName;
    private LocalDateTime completedTime;
    private String completedBy;
    private String completedByName;
    private Long durationSeconds;
    private String inspectionSnapshot;
    private Long deviceRecordId;
    private LocalDateTime cancelledTime;
    private String cancelledBy;
    private String cancelledByName;
    private String cancelReason;
    private Boolean sampleEligible;
    private String exclusionReason;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
