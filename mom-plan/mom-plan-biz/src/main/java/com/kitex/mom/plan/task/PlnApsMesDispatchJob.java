package com.kitex.mom.plan.task;

import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 智能 APS 正式排程异步下达 MES 的调度入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlnApsMesDispatchJob {

    private final PlnApsMesDispatchProcessor processor;

    /**
     * 建议每分钟执行；单次最多领取一百条，部分失败由 Outbox 退避重试。
     */
    @XxlJob("pln_aps_mes_dispatch")
    public void dispatch() {
        int processed = processor.processDue(100);
        log.info("智能APS到MES异步下达完成，本轮处理数量={}", processed);
    }
}
