package com.kitex.mom.iot.network.mqtt.client;

import lombok.Data;

/**
 * MQTT客户端配置
 */
@Data
public class MqttClientConfig {

    /**
     * 客户端ID
     */
    private String id;

    /**
     * 客户端名称
     */
    private String name;

    /**
     * 客户端描述
     */
    private String description;

    /**
     * MQTT Broker地址
     */
    private String brokerUrl;


    /**
     * MQTT Broker端口
     */
    private String port;

    /**
     * MQTT客户端ID
     */
    private String clientId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码
     */
    private String password;

    /**
     * 心跳间隔时间(秒)
     */
    private int keepAliveInterval = 60;

    /**
     * 是否清除会话
     */
    private boolean cleanSession = true;

    /**
     * 连接超时时间(秒)
     */
    private int connectionTimeout = 30;
} 