package com.kitex.mom.qms.task;

import com.kitex.mom.qms.service.impl.QmsPlanEventOutboxProcessor;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * QMS 质量事件 Outbox 定时调度入口，不承载质量业务逻辑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QmsPlanEventOutboxJob {

    private final QmsPlanEventOutboxProcessor processor;

    /**
     * 每条质量事实独立事务投递；失败退避且不阻塞同批其他事实。
     */
    @XxlJob("qms_plan_event_outbox_dispatch")
    public void dispatch() {
        int delivered = processor.dispatchDue(100);
        log.info("QMS质量事实Outbox投递完成，成功数量={}", delivered);
    }
}
