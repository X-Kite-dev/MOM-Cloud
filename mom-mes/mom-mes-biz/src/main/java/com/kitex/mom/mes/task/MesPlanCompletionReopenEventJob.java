package com.kitex.mom.mes.task;

import com.kitex.mom.mes.service.impl.MesPlanCompletionReopenEventService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 周期投递MES完工重新打开事件，业务事务不等待PLAN在线。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MesPlanCompletionReopenEventJob {

    private final MesPlanCompletionReopenEventService service;

    @XxlJob("mes_plan_completion_reopen_dispatch")
    public void dispatch() {
        log.info("MES完工重新打开事件向PLAN投递成功数量={}", service.dispatchDue(100));
    }
}
