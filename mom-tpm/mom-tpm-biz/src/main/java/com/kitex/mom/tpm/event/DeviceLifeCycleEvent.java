package com.kitex.mom.tpm.event;

import com.kitex.mom.tpm.entity.TpmEventRecordEntity;
import org.springframework.context.ApplicationEvent;

public class DeviceLifeCycleEvent extends ApplicationEvent {

    public DeviceLifeCycleEvent(TpmEventRecordEntity source) {
        super(source);
    }
}



