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
