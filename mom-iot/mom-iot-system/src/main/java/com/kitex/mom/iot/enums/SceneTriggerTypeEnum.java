package com.kitex.mom.iot.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 场景触发类型枚举
 */
@Getter
@AllArgsConstructor
public enum SceneTriggerTypeEnum {

    /**
     * 设备触发
     */
    DEVICE("device", "设备触发"),

    /**
     * 定时触发
     */
    SCHEDULED("scheduled", "定时触发"),

    /**
     * 手动触发
     */
    MANUAL("manual", "手动触发");

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
    public static SceneTriggerTypeEnum getByCode(String code) {
        for (SceneTriggerTypeEnum value : values()) {
            if (value.getCode().equals(code)) {
                return value;
            }
        }
        return null;
    }
} 