package com.kitex.mom.iot.core.message.property;

import com.kitex.mom.iot.core.message.DeviceMessage;
import lombok.Data;

import java.util.Map;

@Data
public class ReadPropertyMessage implements DeviceMessage {


    private String deviceId;


    @Override
    public String getDeviceId() {
        return deviceId;
    }

    @Override
    public Map<String, Object> getProperties() {
        return Map.of();
    }

    @Override
    public Map<String, String> getPropertyStates() {
        return Map.of();
    }


}
