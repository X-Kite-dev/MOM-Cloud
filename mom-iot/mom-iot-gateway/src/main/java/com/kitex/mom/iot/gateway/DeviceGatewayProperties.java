package com.kitex.mom.iot.gateway;

import lombok.Builder;

import java.util.HashMap;
import java.util.Map;

/**
 * 设备网关属性类，用于配置设备网关
 */
@Builder
public class DeviceGatewayProperties {

    /**
     * 网关ID
     */
    private String id;

    /**
     * 网关名称
     */
    private String name;

    /**
     * 网关描述
     */
    private String description;

    /**
     * 网关提供商 必须要有，根据选择的网络类型进行自动设置 mqtt-client-gateway
     */
    private String provider;

    /**
     * 网络组件ID
     */
    private String channelId;

    /**
     * 协议类型
     */
    private String protocol;

    /**
     * 传输协议
     */
    private String transport;

    /**
     * 配置参数
     */
    private Map<String, Object> configuration = new HashMap<>();

    /**
     * 获取网关ID
     *
     * @return 网关ID
     */
    public String getId() {
        return id;
    }

    /**
     * 设置网关ID
     *
     * @param id 网关ID
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * 获取网关名称
     *
     * @return 网关名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置网关名称
     *
     * @param name 网关名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取网关描述
     *
     * @return 网关描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 设置网关描述
     *
     * @param description 网关描述
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * 获取网关提供商
     *
     * @return 网关提供商
     */
    public String getProvider() {
        return provider;
    }

    /**
     * 设置网关提供商
     *
     * @param provider 网关提供商
     */
    public void setProvider(String provider) {
        this.provider = provider;
    }

    /**
     * 获取网络组件ID
     *
     * @return 网络组件ID
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * 设置网络组件ID
     *
     * @param channelId 网络组件ID
     */
    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    /**
     * 获取协议类型
     *
     * @return 协议类型
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * 设置协议类型
     *
     * @param protocol 协议类型
     */
    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    /**
     * 获取传输协议
     *
     * @return 传输协议
     */
    public String getTransport() {
        return transport;
    }

    /**
     * 设置传输协议
     *
     * @param transport 传输协议
     */
    public void setTransport(String transport) {
        this.transport = transport;
    }

    /**
     * 获取配置参数
     *
     * @return 配置参数
     */
    public Map<String, Object> getConfiguration() {
        return configuration;
    }

    /**
     * 设置配置参数
     *
     * @param configuration 配置参数
     */
    public void setConfiguration(Map<String, Object> configuration) {
        this.configuration = configuration;
    }

    /**
     * 获取配置值
     *
     * @param key 键
     * @param <T> 值类型
     * @return 值
     */
    @SuppressWarnings("unchecked")
    public <T> T getValue(String key) {
        return (T) configuration.get(key);
    }

    /**
     * 获取带默认值的配置值
     *
     * @param key          键
     * @param defaultValue 默认值
     * @param <T>          值类型
     * @return 值
     */
    @SuppressWarnings("unchecked")
    public <T> T getValue(String key, T defaultValue) {
        return (T) configuration.getOrDefault(key, defaultValue);
    }

    /**
     * 设置配置值
     *
     * @param key   键
     * @param value 值
     * @return 当前对象
     */
    public DeviceGatewayProperties value(String key, Object value) {
        configuration.put(key, value);
        return this;
    }

    /**
     * 获取所有配置值
     *
     * @return 配置值
     */
    public Map<String, Object> values() {
        return configuration;
    }
} 