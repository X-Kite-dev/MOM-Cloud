package com.kitex.mom.iot.gateway;

import java.util.List;

/**
 * 设备网关属性管理器
 */
public interface DeviceGatewayPropertiesManager {

    /**
     * 获取网关的属性
     *
     * @param id 网关ID
     * @return 网关属性
     */
    DeviceGatewayProperties getProperties(String id);

    List<DeviceGatewayProperties> getPropertiesByChannel(String channel);

}
