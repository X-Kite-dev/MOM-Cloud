package com.kitex.mom.mes.task;

import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** MES ERP执行凭证转交调度入口。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MesErpPostingOutboxJob {
    private final MesErpPostingOutboxWorker worker;

    @XxlJob("mes_erp_posting_outbox")
    public void transfer() {
        log.info("MES ERP凭证本轮转交数量={}", worker.processDue(100));
    }
}
