package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_scheduling_effect_observation")
@EqualsAndHashCode(callSuper = true)
public class PlnSchedulingEffectObservationEntity extends BaseEntity<PlnSchedulingEffectObservationEntity> {
    private Long reviewId;
    private Long scenarioId;
    private String requestId;
    private String requestFingerprint;
    private String observationType;
    private String observationStatus;
    private Integer toleranceMinutes;
    private String reason;
    private String actualSnapshot;
    private String actualFingerprint;
    private Integer mesTaskCount;
    private Integer matchedOperationCount;
    private Integer startedOperationCount;
    private Integer completedOperationCount;
    private BigDecimal completionRate;
    private BigDecimal startAdherenceRate;
    private BigDecimal finishAdherenceRate;
    private BigDecimal scheduleAdherenceRate;
    private BigDecimal averageAbsoluteStartDeviationMinutes;
    private Long maximumAbsoluteStartDeviationMinutes;
    private BigDecimal averageAbsoluteFinishDeviationMinutes;
    private Long maximumAbsoluteFinishDeviationMinutes;
    private Long totalSignedStartDelayMinutes;
    private Long totalSignedFinishDelayMinutes;
    private Integer completedIssueCount;
    private Integer onTimeIssueCount;
    private Integer lateIssueCount;
    private BigDecimal actualEffectScore;
    private BigDecimal realizationGap;
    private String scoreFormulaVersion;
    private String conclusion;
    private String errorMessage;
    private String observedBy;
    private String observedByName;
    private LocalDateTime observedTime;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
