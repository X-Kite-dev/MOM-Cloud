package com.kitex.mom.iot.network.mqtt.server;

import lombok.Data;

/**
 * MQTT服务器配置
 */
@Data
public class MqttServerConfig {

    /**
     * 服务器ID
     */
    private String id;

    /**
     * 服务器名称
     */
    private String name;

    /**
     * 服务器描述
     */
    private String description;

    /**
     * 服务器地址
     */
    private String host;

    /**
     * 服务器端口
     */
    private int port;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码
     */
    private String password;

    /**
     * 保活时间
     */
    private int keepAlive;

    /**
     * 是否清理会话
     */
    private boolean cleanSession;

    /**
     * 是否自动重连
     */
    private boolean autoReconnect;

    /**
     * 最大消息大小
     */
    private int maxMessageSize;
}