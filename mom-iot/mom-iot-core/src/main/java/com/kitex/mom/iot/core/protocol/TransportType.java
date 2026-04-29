package com.kitex.mom.iot.core.protocol;

/**
 * 传输类型枚举
 * 定义了支持的所有传输协议类型
 */
public enum TransportType {
    /**
     * MQTT协议
     */
    MQTT,

    /**
     * HTTP协议
     */
    HTTP,

    /**
     * TCP协议
     */
    TCP,

    /**
     * UDP协议
     */
    UDP,

    /**
     * CoAP协议
     */
    COAP;


    // 根据名字获取枚举
    public static TransportType getByName(String name) {
        for (TransportType transportType : TransportType.values()) {
            if (transportType.name().equals(name)) {
                return transportType;
            }
        }
        return null;
    }

} 