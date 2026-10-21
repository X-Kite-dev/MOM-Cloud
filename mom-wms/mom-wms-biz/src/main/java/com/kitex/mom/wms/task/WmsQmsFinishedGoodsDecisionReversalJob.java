package com.kitex.mom.wms.task;

import com.kitex.mom.wms.service.WmsQmsFinishedGoodsDecisionReversalOutboxService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 周期回送WMS成品实物红冲的QMS永久执行结果。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WmsQmsFinishedGoodsDecisionReversalJob {
    private final WmsQmsFinishedGoodsDecisionReversalOutboxService service;

    @XxlJob("wms_qms_finished_goods_decision_reversal_dispatch")
    public void dispatch() {
        log.info("WMS成品质量逆向执行结果投递完成，本轮QMS确认数={}", service.processDue(100));
    }
}
