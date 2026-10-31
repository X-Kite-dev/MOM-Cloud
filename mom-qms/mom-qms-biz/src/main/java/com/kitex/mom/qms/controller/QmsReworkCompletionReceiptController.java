package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.qms.service.impl.QmsReworkCompletionReceiptService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/inspection/rework-completion-receipt")
@RequiredArgsConstructor
public class QmsReworkCompletionReceiptController {
    private final QmsReworkCompletionReceiptService receipts;

    @GetMapping("/product")
    @PreAuthorize("@pms.hasPermission('productTask:show')")
    public R<QmsReworkCompletionReceiptService.View> product(@RequestParam Long orderId) {
        return R.ok(receipts.view("PRODUCT", orderId));
    }

    @GetMapping("/procedure")
    @PreAuthorize("@pms.hasPermission('procedureTask:show')")
    public R<QmsReworkCompletionReceiptService.View> procedure(@RequestParam Long orderId) {
        return R.ok(receipts.view("PROCEDURE", orderId));
    }
}
