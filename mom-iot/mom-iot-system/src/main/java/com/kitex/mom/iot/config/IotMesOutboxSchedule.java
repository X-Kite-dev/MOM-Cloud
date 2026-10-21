package com.kitex.mom.iot.config;
import com.kitex.mom.iot.service.impl.IotMesOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
/** 先执行累计SQL第100节并部署MES回执接口，再显式启用。多实例通过数据库租约竞争。 */
@Configuration @EnableScheduling @RequiredArgsConstructor
@ConditionalOnProperty(name="mom.iot.mes-outbox.enabled",havingValue="true")
public class IotMesOutboxSchedule {
    private final IotMesOutboxService service;
    @Scheduled(fixedDelayString="${mom.iot.mes-outbox.delay-ms:30000}",initialDelay=30000)
    public void dispatch() {service.processDue();}
}
