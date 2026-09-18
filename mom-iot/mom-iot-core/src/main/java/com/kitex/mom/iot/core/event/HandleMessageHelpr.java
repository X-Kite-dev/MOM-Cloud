package com.kitex.mom.iot.core.event;

import com.kitex.mom.iot.core.message.DeviceMessage;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 抛出解析后的设备消息事件
 */
@Component
@AllArgsConstructor
@Slf4j
public class HandleMessageHelpr {

    private final ApplicationEventPublisher applicationEventPublisher;

    public void handleMessage(String gatewayId, DeviceMessage message) {
        if (gatewayId == null || message == null) throw new IllegalArgumentException("采集缺少可信网关或解码消息");
        // 失败向调用端传播；是否重投仍由具体协议与边缘持久化保证，不在此吞错冒充接收成功。
        applicationEventPublisher.publishEvent(new DeviceMessageEvent(gatewayId, message));
    }

    public void handleMessage(DeviceMessage encodedMessage) {

        log.info("收到要处理的消息 -》 {}", encodedMessage);
        try {
//            ApplicationEventPublisher eventPublisher = SpringUtil.getBean(ApplicationEventPublisher.class);
            // 抛出事件
            applicationEventPublisher.publishEvent(new DeviceMessageEvent(encodedMessage));

        } catch (Exception e) {
            log.error("处理设备消息失败", e);
        }


    }
}
