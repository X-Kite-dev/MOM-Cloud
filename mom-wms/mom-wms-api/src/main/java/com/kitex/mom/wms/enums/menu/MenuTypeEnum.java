package com.kitex.mom.wms.enums.menu;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 菜单类型
 *
 * @author kitex
 * @date 2025-1-14 10:53:43
 **/
@Getter
@AllArgsConstructor
public enum MenuTypeEnum {

    /**
     * 左菜单
     */
    LEFT_MENU("0", "左菜单"),
    /**
     * 按钮
     */
    BUTTON("1", "按钮"),
    /**
     * 顶菜单
     */
    TOP_MENU("2", "顶菜单");

    private final String code;

    private final String name;

    /**
     * 根据编码获取枚举
     *
     * @param code 编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static MenuTypeEnum getByCode(String code) {
        for (MenuTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
