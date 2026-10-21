package com.kitex.mom.wms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.wms.dto.WmsMesIssueCommand;
import com.kitex.mom.wms.service.impl.WmsMesIssueOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/wmsMesIssueOutbox")
public class WmsMesIssueOutboxController {
    private final WmsMesIssueOutboxService service;

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('wms_mesIssue_view')")
    public R<?> page(@RequestParam String factoryCode, @RequestParam(required = false) String billNo,
                     @RequestParam(required = false) String status, @RequestParam(defaultValue = "1") long current,
                     @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.page(factoryCode, billNo, status, current, size));
    }

    @GetMapping("/{id}/operations")
    @PreAuthorize("@pms.hasPermission('wms_mesIssue_view')")
    public R<?> operations(@PathVariable Long id) {
        return R.ok(service.operations(id));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('wms_mesIssue_view')")
    public R<?> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @GetMapping("/{id}/evidence")
    @PreAuthorize("@pms.hasPermission('wms_mesIssue_view')")
    public R<?> evidence(@PathVariable Long id) {
        return R.ok(service.evidence(id));
    }

    @PostMapping("/retry")
    @SysLog("人工续跑WMS发料投递")
    @PreAuthorize("@pms.hasPermission('wms_mesIssue_retry')")
    public R<?> retry(@RequestBody WmsMesIssueCommand command) {
        return R.ok(service.retry(command));
    }

    @PostMapping("/backfill")
    @SysLog("历史已完成发料补建MES投递")
    @PreAuthorize("@pms.hasPermission('wms_mesIssue_backfill')")
    public R<?> backfill(@RequestBody WmsMesIssueCommand command) {
        return R.ok(service.backfill(command));
    }
}
