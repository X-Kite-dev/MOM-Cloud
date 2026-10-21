package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesMaterialReturnReceiptEvent;
import com.kitex.mom.mes.service.impl.MesMaterialReturnReceiptInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * WMS生产退料实收的MES内部可靠回写入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesMaterialReturnReceipt/internal")
public class MesMaterialReturnReceiptInboxController {
    private final MesMaterialReturnReceiptInboxService service;

    @Inner
    @PostMapping("/receive")
    public R<MesMaterialReturnReceiptEvent.Receipt> receive(@RequestBody MesMaterialReturnReceiptEvent event) {
        return R.ok(service.receive(event));
    }

    @Inner
    @GetMapping("/receipt")
    public R<MesMaterialReturnReceiptEvent.Receipt> receipt(@RequestParam String eventId) {
        return R.ok(service.receipt(eventId));
    }
}
