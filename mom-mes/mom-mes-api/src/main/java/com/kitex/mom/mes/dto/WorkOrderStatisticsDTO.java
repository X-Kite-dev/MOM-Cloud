package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 工单统计数据传输对象
 *
 * @author system
 * @since 2025-01-09
 */
@Data
@Schema(description = "按计划结束时间归日的到期工单当前状态；无计划结束时间不入趋势。完成率分母为有效工单数")
public class WorkOrderStatisticsDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 统计日期
     */
    @Schema(description = "统计日期")
    private LocalDate statisticsDate;

    /**
     * 统计日期字符串（用于图表显示）
     */
    @Schema(description = "统计日期字符串")
    private String dateStr;

    /**
     * 工单总数
     */
    @Schema(description = "工单总数")
    private Long totalCount;

    /**
     * 已完成工单数
     */
    @Schema(description = "已完成工单数")
    private Long completedCount;

    /**
     * 进行中工单数
     */
    @Schema(description = "进行中工单数")
    private Long inProgressCount;

    /**
     * 未开始工单数
     */
    @Schema(description = "未开始工单数")
    private Long notStartedCount;

    /**
     * 已暂停工单数
     */
    @Schema(description = "已暂停工单数")
    private Long pausedCount;

    /**
     * 已取消工单数
     */
    @Schema(description = "已取消工单数")
    private Long cancelledCount;

    /**
     * 完成率（百分比）
     */
    @Schema(description = "完成率")
    private Double completionRate;

    /**
     * 按时完成数
     */
    @Schema(description = "按时完成数")
    private Long onTimeCompletedCount;

    /**
     * 延期完成数
     */
    @Schema(description = "延期完成数")
    private Long delayedCompletedCount;

    /**
     * 按时完成率（百分比）
     */
    @Schema(description = "按时完成率")
    private Double onTimeCompletionRate;

    @Schema(description = "关闭工单数（与已完成数可能重叠；关闭本身不证明完工）")
    private Long closedCount;
    @Schema(description = "未知工单状态数，不参与完成率分母")
    private Long unknownStatusCount;
    @Schema(description = "完成率分母：正式未关闭工单及有真实完工时间的关闭工单")
    private Long eligibleCount;
    @Schema(description = "已完成但缺少完工时间的工单数，不能判定按时或延期")
    private Long unknownCompletionTimeCount;
    @Schema(description = "截至查询时已过计划结束时间但尚未完成的有效工单数")
    private Long overdueUnfinishedCount;
}
