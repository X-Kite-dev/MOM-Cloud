package com.kitex.mom.pms.task;

import com.kitex.mom.pms.service.PmsAgentPurchaseStatusOutboxService;
import com.kitex.mom.pms.service.impl.PmsAgentPurchaseStatusSourceReconciler;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PmsAgentPurchaseStatusOutboxJob {
    private final PmsAgentPurchaseStatusOutboxService service;
    private final PmsAgentPurchaseStatusSourceReconciler sourceReconciler;

    @XxlJob("pms_agent_purchase_status_outbox_dispatch")
    public void dispatch() {
        int reconciled = sourceReconciler.reconcileMissing(100);
        int delivered = service.dispatchDue(100);
        log.info("PMS Agent采购状态Outbox调度完成，缺失终止供给事件补入数量={}，投递成功数量={}", reconciled, delivered);
    }
}
