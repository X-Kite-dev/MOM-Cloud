package com.kitex.mom.wms.task;

import com.kitex.mom.wms.service.impl.WmsOmsCustomerExceptionResolutionOutboxService;
import com.kitex.mom.wms.service.impl.WmsOmsCustomerReceiptOutboxService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WmsOmsCustomerReceiptJob {
    private final WmsOmsCustomerReceiptOutboxService service;
    private final WmsOmsCustomerExceptionResolutionOutboxService resolutionService;

    @XxlJob("wms_oms_customer_receipt_dispatch")
    public void dispatch() {
        log.info("WMS→OMS客户实收投递完成，本轮成功数={}", service.processDue(100));
    }

    @XxlJob("wms_oms_customer_receipt_reconcile")
    public void reconcile() {
        log.info("WMS→OMS客户实收对账完成，本轮核验数={}", service.processReconciliationDue(100));
    }

    @XxlJob("wms_oms_customer_exception_resolution_dispatch")
    public void resolutionDispatch() {
        log.info("WMS→OMS客户异常结案投递完成，本轮成功数={}", resolutionService.processDue(100));
    }

    @XxlJob("wms_oms_customer_exception_resolution_reconcile")
    public void resolutionReconcile() {
        log.info("WMS→OMS客户异常结案对账完成，本轮核验数={}", resolutionService.processReconciliationDue(100));
    }
}
