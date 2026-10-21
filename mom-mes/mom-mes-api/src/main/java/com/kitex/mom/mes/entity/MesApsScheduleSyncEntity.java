package com.kitex.mom.mes.entity;

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
@TableName("mes_aps_schedule_sync")
@EqualsAndHashCode(callSuper = true)
public class MesApsScheduleSyncEntity extends BaseEntity<MesApsScheduleSyncEntity> {
    private String requestId;
    private String requestSnapshot;
    private String planNo;
    private Long replanCaseId;
    private String beforeExecutionFingerprint;
    private String afterExecutionFingerprint;
    private Integer updatedOperationCount;
    private String beforeScheduleSnapshot;
    private String afterScheduleSnapshot;
    private String syncStatus;
    private LocalDateTime appliedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
