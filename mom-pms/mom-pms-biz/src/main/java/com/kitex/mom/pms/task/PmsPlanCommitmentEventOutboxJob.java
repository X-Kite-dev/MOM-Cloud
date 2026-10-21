package com.kitex.mom.pms.task;

import com.kitex.mom.pms.service.PmsPlanCommitmentEventOutboxService;
import com.kitex.mom.pms.service.impl.PmsPlanCommitmentEventSourceReconciler;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PmsPlanCommitmentEventOutboxJob {
    private final PmsPlanCommitmentEventOutboxService service;
    private final PmsPlanCommitmentEventSourceReconciler sourceReconciler;

    @XxlJob("pms_plan_commitment_event_outbox_dispatch")
    public void dispatch() {
        int reconciled = sourceReconciler.reconcileMissing(100);
        int delivered = service.dispatchDue(100);
        log.info("PMS承诺事件Outbox调度完成，历史承诺补入数量={}，投递成功数量={}", reconciled, delivered);
    }
}
