package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.qms.service.impl.QmsMesReceiptReconcileService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/bad/mes-reconcile")
public class QmsMesReceiptReconcileController {
    private final QmsMesReceiptReconcileService service;

    @GetMapping
    @PreAuthorize("@pms.hasPermission('bad:show','bad:mes:reconcile')")
    public R<?> page(@RequestParam(defaultValue = "1") long page, @RequestParam(required = false) String status, @RequestParam(required = false) String ncrNo) {
        return R.ok(service.page(page, status, ncrNo));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('bad:show','bad:mes:reconcile')")
    public R<?> detail(@PathVariable Long id, @RequestParam(defaultValue = "1") long page) {
        return R.ok(service.detail(id, page));
    }

    @PostMapping("/retry")
    @PreAuthorize("@pms.hasPermission('bad:mes:reconcile')")
    public R<?> retry(@RequestBody QmsMesReceiptReconcileService.Retry command) {
        service.retry(command);
        return R.ok();
    }
}
