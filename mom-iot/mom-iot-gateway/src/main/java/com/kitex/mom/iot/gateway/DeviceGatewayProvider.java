package com.kitex.mom.iot.gateway;

/**
 * 设备网关提供商接口，用于创建和管理设备网关
 */
public interface DeviceGatewayProvider {

    /**
     * 网络通道
     */
    String CHANNEL_NETWORK = "network";

    /**
     * 获取唯一标识
     *
     * @return 唯一标识
     */
    String getId();

    /**
     * 获取名称
     *
     * @return 名称
     */
    String getName();

    /**
     * 获取接入说明
     *
     * @return 接入说明
     */
    default String getDescription() {
        return null;
    }

    /**
     * 获取接入通道
     *
     * @return 接入通道
     */
    default String getChannel() {
        return CHANNEL_NETWORK;
    }

    /**
     * 获取排序
     *
     * @return 排序
     */
    default int getOrder() {
        return Integer.MAX_VALUE;
    }

    /**
     * 获取传输协议
     *
     * @return 传输协议
     */
    Transport getTransport();

    /**
     * 创建设备网关
     *
     * @param properties 属性
     * @return 设备网关
     */
    DeviceGateway createDeviceGateway(DeviceGatewayProperties properties);

    /**
     * 重新加载网关
     *
     * @param gateway    原网关
     * @param properties 新属性
     * @return 新网关
     */
    default DeviceGateway reloadDeviceGateway(DeviceGateway gateway, DeviceGatewayProperties properties) {
        return gateway;
    }
} 