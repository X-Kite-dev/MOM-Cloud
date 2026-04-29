package com.kitex.mom.mdm.utils;

import com.kitex.mom.mdm.entity.MdmClassesEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ShiftCalculator {

    public static String getShift(List<MdmClassesEntity> rules, LocalDateTime dateTime) {
        LocalDate date = dateTime.toLocalDate();
        LocalTime time = dateTime.toLocalTime();

        for (MdmClassesEntity rule : rules) {
            if (rule.getEndTime().isBefore(rule.getStartTime())) {
                // 处理跨天的班次规则
                if (time.isBefore(rule.getEndTime())) {
                    // 若时间在跨天班次结束时间之前，日期需减一天
                    date = date.minusDays(1);
                }
                if (time.isAfter(rule.getStartTime()) || time.isBefore(rule.getEndTime())) {
                    return formatDate(date) + rule.getShiftCode();
                }
            } else {
                if (time.isAfter(rule.getStartTime()) && time.isBefore(rule.getEndTime())) {
                    return formatDate(date) + rule.getShiftCode();
                }
            }
        }
        return null; // 如果没有匹配的班次规则
    }

    private static String formatDate(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("yyMMdd"));
    }
}