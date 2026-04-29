package com.kitex.mom.tpm.event;

import com.kitex.mom.tpm.entity.TpmEventRecordEntity;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.function.Consumer;

/**
 * 事件发布工具类
 */
@Component
public class EventPublisherUtil {

    private final ApplicationEventPublisher publisher;

    public EventPublisherUtil(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 发布设备生命周期事件
     *
     * @param customizer 事件记录定制器
     */
    public void publishDeviceLifeCycleEvent(Consumer<TpmEventRecordEntity> customizer) {
        TpmEventRecordEntity eventRecord = new TpmEventRecordEntity();
        // 设置通用属性
        eventRecord.setOccurredAt(LocalDateTime.now());
        // 应用自定义设置
        if (customizer != null) {
            customizer.accept(eventRecord);
        }
        publisher.publishEvent(new DeviceLifeCycleEvent(eventRecord));
    }
} 