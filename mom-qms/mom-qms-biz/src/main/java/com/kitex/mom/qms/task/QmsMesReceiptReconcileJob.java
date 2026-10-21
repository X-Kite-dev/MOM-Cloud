package com.kitex.mom.qms.task;

import com.kitex.mom.qms.service.impl.QmsMesReceiptReconcileService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class QmsMesReceiptReconcileJob {
    private final QmsMesReceiptReconcileService service;

    @XxlJob("qms_mes_receipt_reconcile")
    public void reconcile() {
        log.info("MES实物回执自动对账结果：{}", service.scan());
    }
}
