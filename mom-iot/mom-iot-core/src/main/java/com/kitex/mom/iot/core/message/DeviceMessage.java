package com.kitex.mom.iot.core.message;

import java.util.Map;

/**
 * 设备解码之后的消息
 */
public interface DeviceMessage {

    String getDeviceId();


    Map<String, Object> getProperties();


    Map<String, String> getPropertyStates();


}
