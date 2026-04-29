package com.kitex.mom.iot.core.message.codec;

import com.kitex.mom.iot.core.device.DeviceOperator;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * 编解码上下文
 */
public interface MessageCodecContext {

    /**
     * 获取上下文中的操作设备接口
     *
     * @return
     */
    DeviceOperator getDevice();


    default DeviceOperator getDevice(String deviceId) {
        return null;
    }


    /**
     * 获取配置信息
     *
     * @return
     */
    default Map<String, Object> getConfiguration() {
        return Collections.emptyMap();
    }

    /**
     * 获取配置信息
     *
     * @param key
     * @return
     */
    default Optional<Object> getConfig(String key) {
        return Optional
                .ofNullable(getConfiguration())
                .map(conf -> conf.get(key));
    }
}
