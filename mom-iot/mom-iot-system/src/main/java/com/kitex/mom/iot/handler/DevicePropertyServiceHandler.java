package com.kitex.mom.iot.handler;

import com.kitex.mom.iot.core.event.DeviceMessageEvent;
import com.kitex.mom.iot.core.message.DeviceMessage;
import com.kitex.mom.iot.service.IotDevicePropertyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 设备属性处理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevicePropertyServiceHandler {

    private final IotDevicePropertyService devicePropertyService;


    @EventListener
    public void handleCustomEvent(DeviceMessageEvent event) {
        log.info("Received custom event - Message: " + event.getMessage());
        DeviceMessage message = event.getMessage();
        handlePropertyReport(message.getDeviceId(), message.getProperties());
    }


    public boolean handlePropertyReport(String deviceId, Map<String, Object> properties) {
        log.info("处理设备属性上报: deviceId={}, properties={}", deviceId, properties);

        if (properties == null || properties.isEmpty()) {
            log.warn("属性数据为空，无需处理");
            return false;
        }

        // 调用设备属性服务更新属性
        return devicePropertyService.updateDeviceProperties(deviceId, properties);
    }
} 