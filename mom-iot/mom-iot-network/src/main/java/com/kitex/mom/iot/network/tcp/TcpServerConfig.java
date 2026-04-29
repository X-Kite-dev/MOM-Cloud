package com.kitex.mom.iot.network.tcp;

import lombok.Data;

/**
 * TCP服务器配置
 */
@Data
public class TcpServerConfig {

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
     * 本地地址
     */
    private String localAddress;

    /**
     * 本地端口
     */
    private int localPort;

    /**
     * 最大连接数
     */
    private int maxConnections;

    /**
     * 最大消息大小
     */
    private int maxMessageSize;
} 