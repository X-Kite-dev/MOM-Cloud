package com.kitex.mom.mdm.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 仓库类型num
 *
 * @author kitex
 * @date 2025-04-25 13:46:08
 */
@Getter
@AllArgsConstructor
public enum WarehouseTypeEnum {

    OUTSOURCE("outsource", "外协"),

    MATERIAL("material", "原辅库"),

    PRODUCT("product", "成品库");

    /**
     * 编码
     */
    private String code;

    /**
     * 描述
     */
    private String label;

    public static String getLabelByCode(String code) {
        for (WarehouseTypeEnum calendarEnum : WarehouseTypeEnum.values()) {
            if (calendarEnum.getCode().equals(code)) {
                return calendarEnum.getLabel();
            }
        }
        return null;
    }

}
