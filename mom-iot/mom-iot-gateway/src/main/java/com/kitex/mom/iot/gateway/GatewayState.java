package com.kitex.mom.iot.gateway;

/**
 * 网关状态枚举，定义了网关的可能状态
 */
public enum GatewayState {
    /**
     * 正在启动
     */
    starting,

    /**
     * 已启动
     */
    started,

    /**
     * 已暂停
     */
    paused,

    /**
     * 已关闭
     */
    shutdown
} 