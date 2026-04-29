package com.kitex.mom.iot.constant;

import lombok.Getter;

/**
 * 连接类型枚举
 *
 * @author system
 * @date 2025-03-12
 */
public enum ConnectTypeEnum {

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
     * WebSocket协议
     */
    WEBSOCKET("websocket", "WebSocket");

    @Getter
    private final String code;

    @Getter
    private final String label;

    ConnectTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static String labelOf(String code) {
        if (code == null) {
            return "";
        }
        for (ConnectTypeEnum item : ConnectTypeEnum.values()) {
            if (code.equals(item.code)) {
                return item.label;
            }
        }
        return "";
    }
} 