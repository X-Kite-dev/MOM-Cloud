package com.kitex.mom.iot.core.event;

import com.kitex.mom.iot.core.message.DeviceMessage;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class DeviceMessageEvent extends ApplicationEvent {

    private final DeviceMessage message;

    public DeviceMessageEvent(DeviceMessage source) {
        super(source);
        this.message = source;
    }

}