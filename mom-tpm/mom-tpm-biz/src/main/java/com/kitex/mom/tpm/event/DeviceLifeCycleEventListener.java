package com.kitex.mom.tpm.event;

import com.kitex.mom.tpm.entity.TpmEventRecordEntity;
import com.kitex.mom.tpm.service.TpmEventRecordService;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class DeviceLifeCycleEventListener {
    private final TpmEventRecordService tpmEventRecordService;

    @SneakyThrows
    @EventListener(DeviceLifeCycleEvent.class)
    public void handlerDeviceLifeCycleEvent(DeviceLifeCycleEvent event) {
        TpmEventRecordEntity source = (TpmEventRecordEntity) event.getSource();
        tpmEventRecordService.save(source);
    }

}
