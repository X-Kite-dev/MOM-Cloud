package com.kitex.mom.iot.core.defaults;


import com.kitex.mom.iot.core.network.NetworkType;
import com.kitex.mom.iot.core.network.NetworkTypes;

/**
 * 网络类型枚举，支持常见网络服务类型
 */
public enum DefaultNetworkType implements NetworkType {

    /**
     * TCP服务器
     */
    TCP_SERVER("tcp-server", "TCP服务"),

    /**
     * TCP客户端
     */
    TCP_CLIENT("tcp-client", "TCP客户端"),

    /**
     * UDP服务
     */
    UDP_SERVER("udp-server", "UDP服务"),

    /**
     * MQTT服务
     */
    MQTT_SERVER("mqtt-server", "MQTT服务"),

    /**
     * MQTT客户端
     */
    MQTT_CLIENT("mqtt-client", "MQTT客户端"),

    /**
     * HTTP服务
     */
    HTTP_SERVER("http-server", "HTTP服务");

    /**
     * 静态初始化块，注册所有网络类型
     */
    static {
        registerAllTypes();
    }

    private final String id;
    private final String name;

    /**
     * 构造函数
     *
     * @param id   类型ID
     * @param name 类型名称
     */
    DefaultNetworkType(String id, String name) {
        this.id = id;
        this.name = name;
    }

    /**
     * 注册所有网络类型
     */
    public static void registerAllTypes() {
        for (DefaultNetworkType type : values()) {
            NetworkTypes.register(type);
        }
    }

    /**
     * 根据ID获取网络类型
     *
     * @param id 网络类型ID
     * @return 对应的网络类型或null
     */
    public static DefaultNetworkType getType(String id) {
        if (id == null) {
            return null;
        }

        for (DefaultNetworkType type : values()) {
            if (type.getId().equals(id)) {
                return type;
            }
        }
        return null;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }
} 