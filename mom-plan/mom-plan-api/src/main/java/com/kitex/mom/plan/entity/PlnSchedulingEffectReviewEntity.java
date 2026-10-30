package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TenantTable
@TableName("pln_scheduling_effect_review")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "已发布APS候选方案的当前执行效果汇总")
public class PlnSchedulingEffectReviewEntity extends BaseEntity<PlnSchedulingEffectReviewEntity> {
    private String reviewNo;
    private Long scenarioId;
    private String scenarioNo;
    private String triggerType;
    private Long selectedTaskId;
    private String candidateCode;
    private String candidateName;
    private Integer candidateRank;
    private BigDecimal candidateScore;
    private String selectedMetricsSnapshot;
    private String plannedScopeSnapshot;
    private String plannedScopeFingerprint;
    private Integer sourceIssueCount;
    private Integer plannedOperationCount;
    @Schema(description = "WAITING_DISPATCH、WAITING_EXECUTION、IN_PROGRESS、COMPLETED、SOURCE_UNAVAILABLE、DATA_GAP或ROLLED_BACK")
    private String reviewStatus;
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
    private String latestExecutionFingerprint;
    private Long currentObservationId;
    private LocalDateTime firstObservationTime;
    private LocalDateTime lastObservationTime;
    private LocalDateTime completedTime;
    private LocalDateTime nextEvaluationTime;
    private Integer observationCount;
    private String lastError;
    /**
     * 最新已核验观察形成的待确认偏差行动；仅返回字段，不写入本表。
     */
    @TableField(exist = false)
    private PlnSchedulingEffectActionEntity pendingAction;
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}
