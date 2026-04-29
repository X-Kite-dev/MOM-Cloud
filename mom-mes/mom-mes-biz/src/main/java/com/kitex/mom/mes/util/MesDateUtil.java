package com.kitex.mom.mes.util;

import java.util.Calendar;
import java.util.Date;

/**
 * 日期工具类
 *
 * @author kitex
 * @since 2025-04-07
 */
public class MesDateUtil {

    /**
     * 获取指定月份的开始时间
     *
     * @param date        基准日期
     * @param monthOffset 月份偏移量（0表示当前月，-1表示上月，1表示下月）
     * @return 月份开始时间
     */
    public static Date getMonthStart(Date date, int monthOffset) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.add(Calendar.MONTH, monthOffset);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    /**
     * 获取指定月份的结束时间
     *
     * @param date        基准日期
     * @param monthOffset 月份偏移量（0表示当前月，-1表示上月，1表示下月）
     * @return 月份结束时间
     */
    public static Date getMonthEnd(Date date, int monthOffset) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.add(Calendar.MONTH, monthOffset);
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH));
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTime();
    }

    /**
     * 获取指定年份同月的开始时间
     *
     * @param date       基准日期
     * @param yearOffset 年份偏移量（0表示当年，-1表示去年，1表示明年）
     * @return 年份同月开始时间
     */
    public static Date getYearSameMonthStart(Date date, int yearOffset) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.add(Calendar.YEAR, yearOffset);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    /**
     * 获取指定年份同月的结束时间
     *
     * @param date       基准日期
     * @param yearOffset 年份偏移量（0表示当年，-1表示去年，1表示明年）
     * @return 年份同月结束时间
     */
    public static Date getYearSameMonthEnd(Date date, int yearOffset) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.add(Calendar.YEAR, yearOffset);
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH));
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTime();
    }
} 