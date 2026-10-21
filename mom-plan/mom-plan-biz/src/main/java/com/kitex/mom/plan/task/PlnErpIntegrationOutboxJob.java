package com.kitex.mom.plan.task;

import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** ERP可靠投递调度入口。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlnErpIntegrationOutboxJob {
    private final PlnErpIntegrationOutboxWorker worker;

    /** 建议每分钟运行一次。 */
    @XxlJob("pln_erp_integration_outbox")
    public void dispatch() {
        log.info("ERP投递本轮处理数量={}", worker.processDue(100));
    }
}
