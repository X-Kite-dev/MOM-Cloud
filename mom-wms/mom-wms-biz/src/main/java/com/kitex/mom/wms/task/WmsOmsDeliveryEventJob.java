package com.kitex.mom.wms.task;

import com.kitex.mom.wms.service.impl.WmsOmsDeliveryEventOutboxService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WmsOmsDeliveryEventJob {
    private final WmsOmsDeliveryEventOutboxService service;

    /**
     * 建议每分钟调度，部署时注册本XXL-JOB处理器。
     */
    @XxlJob("wms_oms_delivery_event_dispatch")
    public void dispatch() {
        int completed = service.processDue(100);
        log.info("WMS→OMS发运事实回传完成，本轮成功数={}", completed);
    }

    /**
     * 每十分钟调度；成功回执每日重核，异常回执每半小时复核。
     */
    @XxlJob("wms_oms_delivery_event_reconcile")
    public void reconcile() {
        int checked = service.processReconciliationDue(100);
        log.info("WMS→OMS发运事实对账完成，本轮核验数={}", checked);
    }
}
