package com.kitex.mom.iot.gateway.monitor;

import java.util.HashMap;
import java.util.Map;

/**
 * 网关监控器工厂类，用于获取DeviceGatewayMonitor实例
 */
public class GatewayMonitors {

    /**
     * 监控器实例缓存
     */
    private static final Map<String, DeviceGatewayMonitor> MONITORS = new HashMap<>();

    /**
     * 获取设备网关监控器
     *
     * @param gatewayId 网关ID
     * @return 设备网关监控器
     */
    public static DeviceGatewayMonitor getDeviceGatewayMonitor(String gatewayId) {
        return MONITORS.computeIfAbsent(gatewayId, DeviceGatewayMonitor::empty);
    }

    /**
     * 注册设备网关监控器
     *
     * @param monitor 监控器
     */
    public static void register(DeviceGatewayMonitor monitor) {
        MONITORS.put(monitor.getGatewayId(), monitor);
    }

    /**
     * 移除设备网关监控器
     *
     * @param gatewayId 网关ID
     * @return 被移除的监控器
     */
    public static DeviceGatewayMonitor remove(String gatewayId) {
        return MONITORS.remove(gatewayId);
    }
} 