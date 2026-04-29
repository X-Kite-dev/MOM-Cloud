package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;

/**
 * 时间范围枚举
 *
 * @author system
 * @since 2025-01-09
 */
@Getter
@AllArgsConstructor
public enum TimeRangeEnum {

    /**
     * 今天
     */
    TODAY("TODAY", "今天"),

    /**
     * 昨天
     */
    YESTERDAY("YESTERDAY", "昨天"),

    /**
     * 本周
     */
    THIS_WEEK("THIS_WEEK", "本周"),

    /**
     * 上周
     */
    LAST_WEEK("LAST_WEEK", "上周"),

    /**
     * 本月
     */
    THIS_MONTH("THIS_MONTH", "本月"),

    /**
     * 上月
     */
    LAST_MONTH("LAST_MONTH", "上月"),

    /**
     * 近3个月
     */
    LAST_3_MONTHS("LAST_3_MONTHS", "近3个月"),

    /**
     * 近6个月
     */
    LAST_6_MONTHS("LAST_6_MONTHS", "近6个月"),

    /**
     * 近一年
     */
    LAST_YEAR("LAST_YEAR", "近一年"),

    /**
     * 自定义
     */
    CUSTOM("CUSTOM", "自定义");

    /**
     * 枚举值
     */
    private final String value;

    /**
     * 描述
     */
    private final String description;

    /**
     * 根据枚举值获取时间范围
     *
     * @param value 枚举值
     * @return 时间范围枚举
     */
    public static TimeRangeEnum fromValue(String value) {
        for (TimeRangeEnum timeRange : values()) {
            if (timeRange.getValue().equals(value)) {
                return timeRange;
            }
        }
        return THIS_MONTH; // 默认返回本月
    }

    /**
     * 获取开始时间
     *
     * @return 开始时间
     */
    public LocalDateTime getStartTime() {
        LocalDate today = LocalDate.now();
        switch (this) {
            case TODAY:
                return today.atStartOfDay();
            case YESTERDAY:
                return today.minusDays(1).atStartOfDay();
            case THIS_WEEK:
                return today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).atStartOfDay();
            case LAST_WEEK:
                return today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).minusWeeks(1).atStartOfDay();
            case THIS_MONTH:
                return today.with(TemporalAdjusters.firstDayOfMonth()).atStartOfDay();
            case LAST_MONTH:
                return today.with(TemporalAdjusters.firstDayOfMonth()).minusMonths(1).atStartOfDay();
            case LAST_3_MONTHS:
                return today.with(TemporalAdjusters.firstDayOfMonth()).minusMonths(3).atStartOfDay();
            case LAST_6_MONTHS:
                return today.with(TemporalAdjusters.firstDayOfMonth()).minusMonths(6).atStartOfDay();
            case LAST_YEAR:
                return today.with(TemporalAdjusters.firstDayOfMonth()).minusMonths(12).atStartOfDay();
            default:
                return today.with(TemporalAdjusters.firstDayOfMonth()).atStartOfDay();
        }
    }

    /**
     * 获取结束时间
     *
     * @return 结束时间
     */
    public LocalDateTime getEndTime() {
        LocalDate today = LocalDate.now();
        switch (this) {
            case TODAY:
                return today.atTime(LocalTime.MAX);
            case YESTERDAY:
                return today.minusDays(1).atTime(LocalTime.MAX);
            case THIS_WEEK:
                return today.with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY)).atTime(LocalTime.MAX);
            case LAST_WEEK:
                return today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).minusDays(1).atTime(LocalTime.MAX);
            case THIS_MONTH:
                return today.with(TemporalAdjusters.lastDayOfMonth()).atTime(LocalTime.MAX);
            case LAST_MONTH:
                return today.with(TemporalAdjusters.firstDayOfMonth()).minusDays(1).atTime(LocalTime.MAX);
            case LAST_3_MONTHS:
            case LAST_6_MONTHS:
            case LAST_YEAR:
                return today.with(TemporalAdjusters.lastDayOfMonth()).atTime(LocalTime.MAX);
            default:
                return today.with(TemporalAdjusters.lastDayOfMonth()).atTime(LocalTime.MAX);
        }
    }
}