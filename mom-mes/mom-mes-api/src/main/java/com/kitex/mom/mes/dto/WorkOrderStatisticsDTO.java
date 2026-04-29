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
@Schema(description = "工单统计数据")
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
}