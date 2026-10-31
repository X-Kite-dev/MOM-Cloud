package com.kitex.mom.qms.task;

import com.kitex.mom.qms.service.impl.QmsInspectionRevisionCoordinationDispatcher;
import com.xxl.job.core.handler.annotation.XxlJob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

/** 部署后独立补偿准备/取消；日志不带密钥或原件。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QmsInspectionRevisionCoordinationJob {
    private final QmsInspectionRevisionCoordinationDispatcher dispatcher;

    @XxlJob("qms_inspection_revision_coordination_dispatch")
    public void dispatch() {
        log.info("受控修订准备/取消确认数量={}", dispatcher.scan());
    }
}
