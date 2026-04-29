package com.kitex.mom.iot.gateway;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备网关提供商管理器
 * 用于统一管理和注册所有的DeviceGatewayProvider
 */
@Slf4j
public class DeviceGatewayProviders {

    /**
     * 存储所有注册的网关提供商
     */
    private static final Map<String, DeviceGatewayProvider> PROVIDERS = new ConcurrentHashMap<>();

    /**
     * 私有构造函数，防止实例化
     */
    private DeviceGatewayProviders() {
    }

    /**
     * 注册设备网关提供商
     *
     * @param provider 设备网关提供商
     */
    public static void register(DeviceGatewayProvider provider) {
        if (provider != null) {
            PROVIDERS.put(provider.getId(), provider);
            log.info("注册设备网关提供商: {}", provider.getId());
        }
    }

    /**
     * 注销设备网关提供商
     *
     * @param providerId 提供商ID
     * @return 被注销的提供商
     */
    public static DeviceGatewayProvider unregister(String providerId) {
        log.info("注销设备网关提供商: {}", providerId);
        return PROVIDERS.remove(providerId);
    }

    /**
     * 获取指定ID的设备网关提供商
     *
     * @param providerId 提供商ID
     * @return 设备网关提供商
     */
    public static DeviceGatewayProvider getProvider(String providerId) {
        DeviceGatewayProvider deviceGatewayProvider = PROVIDERS.get(providerId);
        if (deviceGatewayProvider != null) {
            log.info("获取设备网关提供商: {}", providerId);
        }
        return deviceGatewayProvider;
    }

    /**
     * 获取所有设备网关提供商
     *
     * @return 设备网关提供商列表
     */
    public static List<DeviceGatewayProvider> getAllProviders() {
        return new ArrayList<>(PROVIDERS.values());
    }

} 