package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesFinishedGoodsReceiptReverseEvent;
import com.kitex.mom.mes.service.MesFinishedGoodsReceiptReverseInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * WMS成品实物红冲的MES内部可靠回写入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/finishedGoodsReceiptReverse/internal")
public class MesFinishedGoodsReceiptReverseInboxController {
    private final MesFinishedGoodsReceiptReverseInboxService service;

    @Inner
    @PostMapping("/receive")
    public R<MesFinishedGoodsReceiptReverseEvent.Receipt> receive(
            @RequestBody MesFinishedGoodsReceiptReverseEvent event) {
        return R.ok(service.receive(event));
    }

    @Inner
    @GetMapping("/receipt")
    public R<MesFinishedGoodsReceiptReverseEvent.Receipt> receipt(@RequestParam String eventId) {
        return R.ok(service.receipt(eventId));
    }
}
