package com.kitex.mom.plan.task;

import com.kitex.mom.plan.service.impl.PlnAutomationEventProcessor;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 制造自动化事件调度作业。
 *
 * <p>作业仅负责周期触发，不承载事件业务逻辑，防止调度组件与计划领域服务形成反向依赖。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlnAutomationEventJob {

    private final PlnAutomationEventProcessor eventProcessor;

    /**
     * 建议每分钟运行；单次最多处理 100 个到期事件，各事件独立事务且互不阻塞。
     */
    @XxlJob("pln_automation_event_dispatch")
    public void dispatch() {
        int succeeded = eventProcessor.processDueEvents(100);
        log.info("制造异常事件分发完成，成功处理数量={}", succeeded);
    }
}
