package com.kitex.mom.wms.task;

import com.kitex.mom.wms.service.impl.WmsOmsShipmentCorrectionOutboxService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WmsOmsShipmentCorrectionJob {
    private final WmsOmsShipmentCorrectionOutboxService service;

    @XxlJob("wms_oms_shipment_correction_dispatch")
    public void dispatch() {
        log.info("WMS→OMS发运纠错投递完成，本轮成功数={}", service.processDue(100));
    }

    @XxlJob("wms_oms_shipment_correction_reconcile")
    public void reconcile() {
        log.info("WMS→OMS发运纠错对账完成，本轮核验数={}", service.processReconciliationDue(100));
    }
}
