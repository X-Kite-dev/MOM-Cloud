package com.kitex.mom.mes.task;

import com.kitex.mom.mes.service.impl.MesRepairResumeEventService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MesRepairResumeEventJob {
    private final MesRepairResumeEventService service;

    /**
     * 每分钟调度一次；MES开工事务只落本地Outbox，不等待PLAN。
     */
    @XxlJob("mes_repair_resume_event_dispatch")
    public void dispatch() {
        log.info("MES维修真实复工向PLAN投递成功数量={}", service.dispatchDue(100));
    }
}
