package com.kitex.mom.wms.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 单据来源
 *
 * @author kitex
 * @date 2025-1-21 14:13:07
 **/
@Getter
@AllArgsConstructor
public enum InOutBillSourceEnum {

    /**
     * app
     */
    APP("app", "移动端"),
    /**
     * pc
     */
    PC("pc", "PC端");

    private final String code;

    private final String name;

    /**
     * 根据类型编码获取枚举
     *
     * @param code 类型编码
     * @return 对应的枚举值，如果不存在则返回null
     */
    public static InOutBillSourceEnum getByCode(String code) {
        for (InOutBillSourceEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }

}
