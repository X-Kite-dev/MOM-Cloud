package com.kitex.mom.mes.task;

import com.kitex.mom.mes.service.impl.MesInspectionEventApplicationDispatcher;
import com.xxl.job.core.handler.annotation.XxlJob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MesInspectionEventApplicationJob {
    private final MesInspectionEventApplicationDispatcher dispatcher;

    @XxlJob("mes_inspection_event_application")
    public void run() {
        log.info("普通检验事件独立应用完成，已提交数量={}", dispatcher.scan());
    }
}
