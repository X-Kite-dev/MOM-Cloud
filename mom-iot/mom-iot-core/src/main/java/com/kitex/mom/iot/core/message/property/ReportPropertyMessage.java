package com.kitex.mom.iot.core.message.property;

import com.kitex.mom.iot.core.message.DeviceMessage;
import lombok.Data;

import java.util.Map;

@Data
public class ReportPropertyMessage implements DeviceMessage {

    private String code;

    private String messageId;

    private String deviceId;

    /**
     * 属性值
     */
    private Map<String, Object> properties;

    /**
     * 属性状态
     */
    private Map<String, String> propertyStates;


    @Override
    public String getDeviceId() {
        return this.deviceId;
    }
}
