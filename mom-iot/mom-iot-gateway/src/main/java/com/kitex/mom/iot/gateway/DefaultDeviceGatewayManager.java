package com.kitex.mom.iot.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认设备网关管理器实现
 */
public class DefaultDeviceGatewayManager implements DeviceGatewayManager {

    /**
     * 设备网关映射
     */
    private final Map<String, DeviceGateway> gateways = new ConcurrentHashMap<>();

    /**
     * 设备网关提供商映射
     */
    private final Map<String, DeviceGatewayProvider> providers = new ConcurrentHashMap<>();


    private final DeviceGatewayPropertiesManager propertiesManager;

    public DefaultDeviceGatewayManager(DeviceGatewayPropertiesManager propertiesManager) {
        this.propertiesManager = propertiesManager;
    }

    /**
     * 注册设备网关提供商
     *
     * @param provider 设备网关提供商
     * @return 设备网关管理器
     */
    public DeviceGatewayManager register(DeviceGatewayProvider provider) {
        providers.put(provider.getId(), provider);
        return this;
    }


    @Override
    public DeviceGateway getGateway(String id) {
        return doGetGateway(id);
    }


    private DeviceGateway doGetGateway(String id) {
        if (id == null) {
            return null;
        }
        return gateways.computeIfAbsent(id, k -> createGateway(id));
    }


    protected DeviceGateway createGateway(String id) {

        DeviceGatewayProperties properties = propertiesManager.getProperties(id);
        if (properties == null) {
            throw new UnsupportedOperationException("网关配置不存在");
        }
        DeviceGateway deviceGateway = getProviderNow(properties.getProvider()).createDeviceGateway(properties);
        return deviceGateway;
    }


    @Override
    public void reload(String gatewayId) {
        this.doReload(gatewayId);
    }

    private void doReload(String gatewayId) {
        DeviceGatewayProperties properties = propertiesManager.getProperties(gatewayId);

        DeviceGatewayProvider provider = getProviderNow(properties.getProvider());

        DeviceGateway deviceGateway = gateways.get(gatewayId);
        if (deviceGateway != null) {
            DeviceGateway reloadDeviceGateway = provider.reloadDeviceGateway(deviceGateway, properties);
            gateways.put(gatewayId, reloadDeviceGateway);
        } else {
            DeviceGateway providerDeviceGateway = provider.createDeviceGateway(properties);
            providerDeviceGateway.startup();
            gateways.put(gatewayId, providerDeviceGateway);

        }
    }

    @Override
    public void shutdown(String gatewayId) {
        DeviceGateway gateway = getGateway(gatewayId);
        if (gateway != null) {
            gateway.shutdown();
        }
    }

    @Override
    public void start(String id) {
        this.doStart(id);
    }

    public void doStart(String id) {
        this.getGateway(id).startup();
    }


    @Override
    public List<DeviceGatewayProvider> getProviders() {
        return new ArrayList<>(providers.values());
    }

    @Override
    public List<DeviceGateway> getGateways() {
        return new ArrayList<>(gateways.values());
    }

    public DeviceGatewayProvider getProviderNow(String provider) {
        return DeviceGatewayProviders.getProvider(provider);
    }

    @Override
    public Optional<DeviceGatewayProvider> getProvider(String provider) {
        return Optional.ofNullable(providers.get(provider));
    }
} 