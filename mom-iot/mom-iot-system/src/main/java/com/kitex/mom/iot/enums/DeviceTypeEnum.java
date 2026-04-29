package com.kitex.mom.iot.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 设备类型枚举
 *
 * @author system
 * @date 2025-03-12
 */
@Getter
@AllArgsConstructor
public enum DeviceTypeEnum {

    DIRECT_DEVICE("0", "直连设备"),
    GATEWAY_DEVICE("1", "网关设备"),
    SUB_DEVICE("2", "网关子设备");

    private final String value;
    private final String label;

    public static String getLabelByValue(String value) {
        if (value == null) {
            return "";
        }
        for (DeviceTypeEnum type : DeviceTypeEnum.values()) {
            if (type.getValue().equals(value)) {
                return type.getLabel();
            }
        }
        return "";
    }
} 