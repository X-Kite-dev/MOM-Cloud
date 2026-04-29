package com.kitex.mom.mdm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 物料属性enum
 *
 * @author kitex
 * @date 2025-04-16 14:19:23
 */
@Getter
@AllArgsConstructor
public enum MatAttributeEnum {

    MES("mes", "自制"),

    PMS("pms", "采购"),

    OUT("out", "外协");

    /**
     * 编码
     */
    private String code;

    /**
     * 描述
     */
    private String label;

    public static MatAttributeEnum findByCode(String code) {
        for (MatAttributeEnum calendarEnum : MatAttributeEnum.values()) {
            if (calendarEnum.getCode().equals(code)) {
                return calendarEnum;
            }
        }
        return null;
    }

}
