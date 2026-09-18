package com.kitex.mom.iot.core.event;

import com.kitex.mom.iot.core.message.DeviceMessage;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class DeviceMessageEvent extends ApplicationEvent {

    private final DeviceMessage message;
    /** 本进程网关实例ID，不读取设备上报载荷中的租户/工厂。 */
    private final String gatewayId;

    public DeviceMessageEvent(DeviceMessage source) {
        this(null, source);
    }

    public DeviceMessageEvent(String gatewayId, DeviceMessage source) {
        super(source);
        this.message = source;
        this.gatewayId = gatewayId;
    }

}
