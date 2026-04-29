package com.kitex.mom.iot.constant;

import lombok.Getter;

/**
 * 协议类型枚举
 *
 * @author system
 * @date 2025-03-12
 */
public enum ProtocolTypeEnum {

    /**
     * MQTT协议
     */
    MQTT("mqtt", "MQTT"),

    /**
     * HTTP协议
     */
    HTTP("http", "HTTP"),

    /**
     * CoAP协议
     */
    COAP("coap", "CoAP"),

    /**
     * TCP协议
     */
    TCP("tcp", "TCP"),

    /**
     * Modbus协议
     */
    MODBUS("modbus", "Modbus");

    @Getter
    private final String code;

    @Getter
    private final String label;

    ProtocolTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static String labelOf(String code) {
        if (code == null) {
            return "";
        }
        for (ProtocolTypeEnum item : ProtocolTypeEnum.values()) {
            if (code.equals(item.code)) {
                return item.label;
            }
        }
        return "";
    }
} 