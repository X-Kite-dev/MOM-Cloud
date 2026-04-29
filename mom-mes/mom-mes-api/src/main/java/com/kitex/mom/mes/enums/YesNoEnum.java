package com.kitex.mom.mes.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 是否
 *
 * @author kitex
 * @date 2025-1-2 16:02:49
 */
@Getter
@AllArgsConstructor
public enum YesNoEnum {

    NO("0", "否"),
    YES("1", "是");

    /**
     * 编码
     */
    private final String code;

    /**
     * 描述
     */
    private final String desc;

    /**
     * 根据类型编码获取枚举
     *
     * @param code 类型编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static YesNoEnum getByCode(Integer code) {
        for (YesNoEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
