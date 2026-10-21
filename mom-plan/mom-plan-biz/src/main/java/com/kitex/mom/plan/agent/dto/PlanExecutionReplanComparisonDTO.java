package com.kitex.mom.plan.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行偏差重排的同事实对照，不是跨时间窗的效率提升或因果评价。
 *
 * <p>旧、新计划共同使用同一不可变MES观察。百分比没有有效分母时返回null，
 * 缺口工序不进入任一组指标，展示截断不影响完整范围的汇总。</p>
 */
public final class PlanExecutionReplanComparisonDTO {

    private PlanExecutionReplanComparisonDTO() {
    }

    /** 对照结论及可追溯的冻结来源。 */
    public record Comparison(
            @Schema(description = "SAME_FACT_SCHEDULE_ALIGNMENT_V1") String formulaVersion,
            @Schema(description = "COMPARABLE、PARTIAL、DATA_GAP或WAITING_EXECUTION") String status,
            @Schema(description = "重排情景ID") Long scenarioId,
            @Schema(description = "已选重排案件ID") Long caseId,
            @Schema(description = "此次幂等采集对应的观察ID，不使用其他时点的观察") Long observationId,
            @Schema(description = "此次事实采集的永久幂等请求号") String captureRequestId,
            @Schema(description = "MES观察时间") LocalDateTime observedTime,
            @Schema(description = "MES事实指纹") String actualFingerprint,
            @Schema(description = "原计划冻结指纹") String originalScheduleFingerprint,
            @Schema(description = "候选方案冻结指纹") String proposalFingerprint,
            @Schema(description = "共同允许偏差分钟数") Integer toleranceMinutes,
            @Schema(description = "本次实际移动工序范围，不包含未移动工序") Integer scopeOperationCount,
            @Schema(description = "具有同范围、同数量、有效MES事实的工序数") Integer comparableOperationCount,
            @Schema(description = "缺口工序数，未计入任何一组汇总") Integer excludedOperationCount,
            @Schema(description = "共同有效样本对照原计划的汇总") Metrics original,
            @Schema(description = "共同有效样本对照重排计划的汇总") Metrics replanned,
            @Schema(description = "有限的逐工序对照展示，汇总仍覆盖全部有效样本") List<Operation> operations,
            @Schema(description = "逐工序展示是否截断") Boolean rowsTruncated,
            @Schema(description = "阻断或局部排除原因") List<String> dataGaps,
            @Schema(description = "同事实对照结论，不宣称生产效率提高") String summary,
            @Schema(description = "范围、分母、正负方向及非因果限制") String interpretation) {
    }

    /** 两组指标使用严格相同的工序、实际时刻和容差。 */
    public record Metrics(
            @Schema(description = "可比较工序数") int operationCount,
            @Schema(description = "已有有效实际开工时刻的工序数") int startedOperationCount,
            @Schema(description = "已有有效实际完工时刻的工序数") int finishedOperationCount,
            @Schema(description = "按共同容差计算的开工遵从工序数，供跨情景精确加权") int startAdherentOperationCount,
            @Schema(description = "按共同容差计算的完工遵从工序数，供跨情景精确加权") int finishAdherentOperationCount,
            @Schema(description = "实际开工与计划开工绝对偏差不超过容差的比例；无开工样本为null") BigDecimal startAdherenceRate,
            @Schema(description = "实际完工与计划完工绝对偏差不超过容差的比例；无完工样本为null") BigDecimal finishAdherenceRate,
            @Schema(description = "已开工工序的正向迟开工分钟总量，提前不抵扣；无开工样本为null") Long totalStartLateMinutes,
            @Schema(description = "已完工工序的正向迟完工分钟总量，提前不抵扣；无完工样本为null") Long totalFinishLateMinutes,
            @Schema(description = "已超过开工容差仍未开工的工序数") int pendingStartOverdueCount) {
    }

    /** 单道工序的旧、新计划与共同MES观察；无实际时间的指标保持未知。 */
    public record Operation(
            @Schema(description = "生产计划号") String issueNo,
            @Schema(description = "工序编码") String processCode,
            @Schema(description = "工序名称") String processName,
            @Schema(description = "MES任务编码") String mesTaskCode,
            @Schema(description = "COMPARABLE或EXCLUDED") String status,
            @Schema(description = "排除原因，为空表示可比较") String gapReason,
            @Schema(description = "原计划开工") LocalDateTime originalStartTime,
            @Schema(description = "原计划完工") LocalDateTime originalEndTime,
            @Schema(description = "重排计划开工") LocalDateTime replannedStartTime,
            @Schema(description = "重排计划完工") LocalDateTime replannedEndTime,
            @Schema(description = "同观察实际开工") LocalDateTime actualStartTime,
            @Schema(description = "同观察实际完工") LocalDateTime actualFinishTime,
            @Schema(description = "对照原计划的实际迟开工分钟数；无实际开工为null") Long originalStartLateMinutes,
            @Schema(description = "对照重排的实际迟开工分钟数；无实际开工为null") Long replannedStartLateMinutes,
            @Schema(description = "对照原计划的实际迟完工分钟数；无实际完工为null") Long originalFinishLateMinutes,
            @Schema(description = "对照重排的实际迟完工分钟数；无实际完工为null") Long replannedFinishLateMinutes,
            @Schema(description = "计划完工时刻偏移分钟数，正数代表延期，不代表效率改善") Long plannedFinishShiftMinutes) {
    }
}
