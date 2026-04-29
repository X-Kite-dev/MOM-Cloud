package com.kitex.mom.iot.gateway;

import java.util.List;
import java.util.Optional;

/**
 * 设备网关管理器接口，用于统一管理设备接入网关等相关信息
 */
public interface DeviceGatewayManager {

    /**
     * 获取接入网关
     *
     * @param id 网关ID
     * @return 设备网关
     */
    DeviceGateway getGateway(String id);

    /**
     * 重新加载网关
     *
     * @param gatewayId 网关ID
     */
    void reload(String gatewayId);

    /**
     * 停止网关
     *
     * @param gatewayId 网关ID
     */
    void shutdown(String gatewayId);

    /**
     * 启动网关
     *
     * @param id 网关ID
     */
    void start(String id);

    /**
     * 获取全部的设备接入网关提供商
     *
     * @return 设备接入网关提供商列表
     */
    List<DeviceGatewayProvider> getProviders();


    List<DeviceGateway> getGateways();

    /**
     * 根据接入提供商标识获取设备接入提供商接口
     *
     * @param provider 提供商标识
     * @return 设备接入提供商
     */
    Optional<DeviceGatewayProvider> getProvider(String provider);
} 