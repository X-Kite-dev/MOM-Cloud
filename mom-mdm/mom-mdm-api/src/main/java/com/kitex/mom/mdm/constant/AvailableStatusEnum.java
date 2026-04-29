package com.kitex.mom.mdm.constant;

import lombok.Getter;

/**
 * 可用状态枚举
 *
 * @author kitex
 * @date 2024-11-05 09:08:56
 */
public enum AvailableStatusEnum {

    /**
     * 启用
     */
    ENABLE(Boolean.TRUE, "启用"),

    /**
     * 禁用
     */
    DISABLE(Boolean.FALSE, "禁用");

    @Getter
    private final Boolean code;

    @Getter
    private final String label;

    AvailableStatusEnum(Boolean code, String label) {
        this.code = code;
        this.label = label;
    }

    public static Boolean codeOf(String label) {
        if (label == null) {
            return null;
        }
        for (AvailableStatusEnum availableStatusEnum : AvailableStatusEnum.values()) {
            if (label.equals(availableStatusEnum.label)) {
                return availableStatusEnum.code;
            }
        }
        return null;
    }

    /**
     * 根据code获取label
     *
     * @param code
     * @return
     */
    public static String labelOf(Boolean code) {
        for (AvailableStatusEnum availableStatusEnum : AvailableStatusEnum.values()) {
            if (availableStatusEnum.code.equals(code)) {
                return availableStatusEnum.label;
            }
        }
        return null;
    }

}
