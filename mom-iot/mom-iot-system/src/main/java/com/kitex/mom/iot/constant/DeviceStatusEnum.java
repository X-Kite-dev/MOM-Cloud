package com.kitex.mom.iot.constant;

import lombok.Getter;

/**
 * 设备状态枚举
 *
 * @author system
 * @date 2025-03-12
 */
public enum DeviceStatusEnum {

    /**
     * 未激活
     */
    INACTIVE(1, "未激活"),

    /**
     * 在线
     */
    ONLINE(2, "在线"),

    /**
     * 离线
     */
    OFFLINE(3, "离线"),

    /**
     * 禁用
     */
    DISABLED(4, "禁用");

    @Getter
    private final Integer code;

    @Getter
    private final String label;

    DeviceStatusEnum(Integer code, String label) {
        this.code = code;
        this.label = label;
    }

    public static String labelOf(Integer code) {
        if (code == null) {
            return "";
        }
        for (DeviceStatusEnum item : DeviceStatusEnum.values()) {
            if (code.equals(item.code)) {
                return item.label;
            }
        }
        return "";
    }
} 