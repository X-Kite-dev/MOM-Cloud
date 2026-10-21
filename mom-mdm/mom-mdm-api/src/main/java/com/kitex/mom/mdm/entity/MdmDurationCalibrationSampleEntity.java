package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("mdm_duration_calibration_sample")
@EqualsAndHashCode(callSuper = true)
public class MdmDurationCalibrationSampleEntity extends BaseEntity<MdmDurationCalibrationSampleEntity> {
    private Long runId;
    private Long proposalId;
    private String sampleType;
    private String sourceSampleId;
    private String sourceFingerprint;
    private String processCode;
    private String materialCode;
    private String stationCode;
    private String deviceCode;
    private String fromToolingCode;
    private String toToolingCode;
    private BigDecimal completedQuantity;
    private Long durationSeconds;
    private BigDecimal normalizedSeconds;
    private LocalDateTime occurredTime;
    private Boolean sourceEligible;
    private Boolean strategyAccepted;
    private String exclusionReason;
    private String sourceSnapshot;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
