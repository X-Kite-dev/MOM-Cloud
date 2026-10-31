package com.kitex.mom.qms.task;

import com.kitex.mom.qms.service.impl.QmsInspectionEventDispatcher;
import com.xxl.job.core.handler.annotation.XxlJob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class QmsInspectionEventOutboxJob {
    private final QmsInspectionEventDispatcher dispatcher;

    @XxlJob("qms_inspection_event_outbox_dispatch")
    public void dispatch() {
        log.info("普通工序检验事件补偿投递完成，MES收件确认数量={}", dispatcher.scan());
    }
}
