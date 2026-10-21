package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("mdm_duration_calibration_run")
@EqualsAndHashCode(callSuper = true)
public class MdmDurationCalibrationRunEntity extends BaseEntity<MdmDurationCalibrationRunEntity> {
    private String runNo;
    private Long policyId;
    private String policyNo;
    private Integer policyVersionNo;
    private String targetType;
    private String scopeType;
    private String processCode;
    private String resourceCode;
    private LocalDateTime sampleStartTime;
    private LocalDateTime sampleEndTime;
    private String status;
    private String evidenceFingerprint;
    private LocalDateTime evidenceGeneratedTime;
    private Integer sourceSampleCount;
    private Integer sourceExcludedCount;
    private Integer strategyExcludedCount;
    private Integer acceptedSampleCount;
    private Integer proposalCount;
    private String failureReason;
    private String policySnapshot;
    private String requestedBy;
    private String requestedByName;
    private LocalDateTime requestedTime;
    private LocalDateTime completedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
