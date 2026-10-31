package com.kitex.mom.qms.task;

import com.kitex.mom.qms.service.impl.QmsReworkResultDispatcher;
import com.xxl.job.core.handler.annotation.XxlJob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class QmsReworkResultOutboxJob {
    private final QmsReworkResultDispatcher dispatcher;

    @XxlJob("qms_rework_result_outbox_dispatch")
    public void dispatch() {
        log.info("返工复检结果补偿投递完成，已确认数量={}", dispatcher.scan());
    }
}
