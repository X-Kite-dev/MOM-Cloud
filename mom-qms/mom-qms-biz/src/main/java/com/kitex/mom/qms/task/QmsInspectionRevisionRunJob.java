package com.kitex.mom.qms.task;

import com.kitex.mom.qms.service.impl.QmsInspectionRevisionRunDispatcher;
import com.xxl.job.core.handler.annotation.XxlJob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class QmsInspectionRevisionRunJob {
    private final QmsInspectionRevisionRunDispatcher dispatcher;

    @XxlJob("qms_inspection_revision_execution_dispatch")
    public void dispatch() {
        log.info("正式质检修订处理数量={}", dispatcher.scan());
    }
}
