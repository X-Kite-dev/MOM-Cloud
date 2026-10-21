package com.kitex.mom.wms.task;

import com.kitex.mom.wms.service.impl.WmsPmsSupplierReturnOutboxService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 采购退供实际出库回写PMS的可靠调度任务。
 */
@Component
@RequiredArgsConstructor
public class WmsPmsSupplierReturnJob {
    private final WmsPmsSupplierReturnOutboxService service;

    @XxlJob("wms_pms_supplier_return_dispatch")
    public void dispatch() {
        service.processDue(50);
    }
}
