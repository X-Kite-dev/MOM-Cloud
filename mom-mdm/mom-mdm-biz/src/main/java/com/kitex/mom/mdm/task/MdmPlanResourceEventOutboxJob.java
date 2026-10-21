package com.kitex.mom.mdm.task;

import com.kitex.mom.mdm.service.MdmPlanResourceEventOutboxService;
import com.kitex.mom.mdm.service.impl.MdmPlanResourceEventSourceReconciler;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MdmPlanResourceEventOutboxJob {
    private final MdmPlanResourceEventOutboxService service;
    private final MdmPlanResourceEventSourceReconciler sourceReconciler;

    /**
     * 建议每分钟运行；每个资源事实独立事务，失败退避且不阻塞同批其他事实。
     */
    @XxlJob("mdm_plan_resource_event_outbox_dispatch")
    public void dispatch() {
        int reconciled = sourceReconciler.reconcileMissing(100);
        int delivered = service.dispatchDue(100);
        log.info("MDM资源事件Outbox调度完成，历史资源补入数量={}，投递成功数量={}", reconciled, delivered);
    }
}
