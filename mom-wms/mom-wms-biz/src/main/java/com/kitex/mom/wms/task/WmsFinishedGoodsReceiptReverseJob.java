package com.kitex.mom.wms.task;

import com.kitex.mom.wms.service.WmsFinishedGoodsReceiptReverseOutboxService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 周期投递WMS成品实物红冲事件。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WmsFinishedGoodsReceiptReverseJob {
    private final WmsFinishedGoodsReceiptReverseOutboxService service;

    @XxlJob("wms_finished_goods_receipt_reverse_dispatch")
    public void dispatch() {
        log.info("WMS成品实物红冲事件投递完成，本轮MES确认数={}", service.processDue(100));
    }
}
