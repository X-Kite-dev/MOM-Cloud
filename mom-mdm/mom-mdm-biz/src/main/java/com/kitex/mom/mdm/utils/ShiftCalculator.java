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
            if (!Boolean.TRUE.equals(rule.getStatus())) {
                continue;
            }
            boolean overnight = rule.getEndTime().isBefore(rule.getStartTime());
            // 班次统一使用 [开始, 结束)，交接时刻只属于下一班。
            boolean matches = overnight
                    ? !time.isBefore(rule.getStartTime()) || time.isBefore(rule.getEndTime())
                    : !time.isBefore(rule.getStartTime()) && time.isBefore(rule.getEndTime());
            if (matches) {
                LocalDate shiftDate = overnight && time.isBefore(rule.getEndTime()) ? date.minusDays(1) : date;
                return formatDate(shiftDate) + rule.getShiftCode();
            }
        }
        return null; // 如果没有匹配的班次规则
    }

    private static String formatDate(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("yyMMdd"));
    }
}
