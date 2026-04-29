package com.kitex.mom.mdm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工作日历enum
 *
 * @author kitex
 * @date 2025-02-17 16:47:21
 */
@Getter
@AllArgsConstructor
public enum CalendarEnum {

    HOLIDAY("public_holiday", "1"),

    WORKDAY("transfer_workday", "0");

    /**
     * 编码
     */
    private String code;

    /**
     * 描述
     */
    private String label;

    public static String getLabelByCode(String code) {
        for (CalendarEnum calendarEnum : CalendarEnum.values()) {
            if (calendarEnum.getCode().equals(code)) {
                return calendarEnum.getLabel();
            }
        }
        return null;
    }

}
