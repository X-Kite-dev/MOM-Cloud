package com.kitex.mom.iot.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 场景动作类型枚举
 */
@Getter
@AllArgsConstructor
public enum SceneActionTypeEnum {

    /**
     * 触发告警
     */
    ALARM("alarm", "触发告警"),

    /**
     * 解除告警
     */
    CLEAR_ALARM("clearAlarm", "解除告警"),

    /**
     * 消息通知
     */
    NOTIFICATION("notification", "消息通知"),

    /**
     * 设备输出
     */
    DEVICE_OUTPUT("deviceOutput", "设备输出");

    /**
     * 类型编码
     */
    private final String code;

    /**
     * 类型名称
     */
    private final String name;

    /**
     * 根据编码获取枚举
     *
     * @param code 编码
     * @return 枚举
     */
    public static SceneActionTypeEnum getByCode(String code) {
        for (SceneActionTypeEnum value : values()) {
            if (value.getCode().equals(code)) {
                return value;
            }
        }
        return null;
    }
} 