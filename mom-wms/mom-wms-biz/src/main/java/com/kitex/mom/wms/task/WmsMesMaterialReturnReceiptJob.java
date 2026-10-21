package com.kitex.mom.wms.task;

import com.kitex.mom.wms.service.impl.WmsMesMaterialReturnReceiptOutboxService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 周期投递WMS生产退料实收事件。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WmsMesMaterialReturnReceiptJob {
    private final WmsMesMaterialReturnReceiptOutboxService service;

    @XxlJob("wms_mes_material_return_receipt_dispatch")
    public void dispatch() {
        log.info("WMS生产退料实收事件投递完成，本轮确认数={}", service.processDue(100));
    }
}
