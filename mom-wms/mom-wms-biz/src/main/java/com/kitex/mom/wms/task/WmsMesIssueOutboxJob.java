package com.kitex.mom.wms.task;

import com.kitex.mom.wms.service.impl.WmsMesIssueOutboxService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WmsMesIssueOutboxJob {
    private final WmsMesIssueOutboxService service;

    /**
     * 建议每分钟调度；跨租户发现后逐租户/工厂恢复上下文。
     */
    @XxlJob("wms_mes_issue_dispatch")
    public void dispatch() {
        log.info("WMS发料通知可靠投递完成，本轮确认数={}", service.processDue(100));
    }
}
