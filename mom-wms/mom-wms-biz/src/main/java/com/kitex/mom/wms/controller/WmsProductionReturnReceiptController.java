package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.wms.dto.WmsProductionReturnReceiptCommand;
import com.kitex.mom.wms.service.impl.WmsProductionReturnReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 生产退料实际收货终结与差异处置。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsProduceReturn/receipt")
public class WmsProductionReturnReceiptController {
    private final WmsProductionReturnReceiptService service;

    @GetMapping("/{noticeId}")
    @PreAuthorize("@pms.hasPermission('wms_return_receipt_view')")
    public R<WmsProductionReturnReceiptCommand.View> detail(@PathVariable Long noticeId) {
        return R.ok(service.detail(noticeId));
    }

    @PostMapping("/finalize")
    @PreAuthorize("@pms.hasPermission('wms_return_receipt_finalize')")
    public R<WmsProductionReturnReceiptCommand.View> finalizeReceipt(
            @RequestBody WmsProductionReturnReceiptCommand.Finalize command) {
        return R.ok(service.finalizeReceipt(command));
    }

    @PostMapping("/dispose")
    @PreAuthorize("@pms.hasPermission('wms_return_receipt_dispose')")
    public R<WmsProductionReturnReceiptCommand.View> dispose(
            @RequestBody WmsProductionReturnReceiptCommand.Disposition command) {
        return R.ok(service.dispose(command));
    }

    @PostMapping("/retry-delivery")
    @PreAuthorize("@pms.hasPermission('wms_return_receipt_retry')")
    public R<WmsProductionReturnReceiptCommand.View> retryDelivery(
            @RequestBody WmsProductionReturnReceiptCommand.RetryDelivery command) {
        return R.ok(service.retryDelivery(command));
    }
}
