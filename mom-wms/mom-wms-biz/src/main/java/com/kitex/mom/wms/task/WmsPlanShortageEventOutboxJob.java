package com.kitex.mom.wms.task;

import com.kitex.mom.wms.service.WmsPlanShortageEventOutboxService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WmsPlanShortageEventOutboxJob {
    private final WmsPlanShortageEventOutboxService service;

    /**
     * 建议每分钟运行；单事件独立事务，失败不阻塞同批其他供应失效事实。
     */
    @XxlJob("wms_plan_shortage_event_outbox_dispatch")
    public void dispatch() {
        log.info("WMS缺料事件Outbox投递完成，成功数量={}", service.dispatchDue(100));
    }
}
