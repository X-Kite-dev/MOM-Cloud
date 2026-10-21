package com.kitex.mom.tpm.task;

import com.kitex.mom.tpm.service.TpmPlanEventOutboxService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TpmPlanEventOutboxJob {
    private final TpmPlanEventOutboxService service;

    /**
     * 建议每分钟运行；每条消息独立事务，失败退避且不阻塞同批其他设备事实。
     */
    @XxlJob("tpm_plan_event_outbox_dispatch")
    public void dispatch() {
        int delivered = service.dispatchDue(100);
        log.info("TPM设备事件Outbox投递完成，成功数量={}", delivered);
    }
}
