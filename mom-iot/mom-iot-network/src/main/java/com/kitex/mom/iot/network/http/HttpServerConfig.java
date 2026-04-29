package com.kitex.mom.iot.network.http;

import lombok.Data;

/**
 * HTTP服务器配置
 */
@Data
public class HttpServerConfig {

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
     * 上下文路径
     */
    private String contextPath;

    /**
     * Boss线程数(接收连接线程数)
     */
    private int bossThreads = 1;

    /**
     * Worker线程数(处理请求线程数)
     */
    private int workerThreads = Runtime.getRuntime().availableProcessors() * 2;

    /**
     * 最大连接数
     */
    private int maxConnections;

    /**
     * 最大消息大小
     */
    private int maxMessageSize;

    /**
     * 是否启用SSL
     */
    private boolean sslEnabled;

    /**
     * 密钥库路径
     */
    private String keystorePath;

    /**
     * 密钥库密码
     */
    private String keystorePassword;
} 