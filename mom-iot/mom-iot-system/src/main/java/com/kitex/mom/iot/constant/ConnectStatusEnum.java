package com.kitex.mom.iot.constant;

import lombok.Getter;

/**
 * 连接状态枚举
 *
 * @author system
 * @date 2025-03-12
 */
public enum ConnectStatusEnum {

    /**
     * 连接中
     */
    CONNECTED(1, "连接中"),

    /**
     * 已断开
     */
    DISCONNECTED(2, "已断开");

    @Getter
    private final Integer code;

    @Getter
    private final String label;

    ConnectStatusEnum(Integer code, String label) {
        this.code = code;
        this.label = label;
    }

    public static String labelOf(Integer code) {
        if (code == null) {
            return "";
        }
        for (ConnectStatusEnum item : ConnectStatusEnum.values()) {
            if (code.equals(item.code)) {
                return item.label;
            }
        }
        return "";
    }
} 