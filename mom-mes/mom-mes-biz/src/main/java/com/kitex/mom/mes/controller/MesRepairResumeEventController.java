package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.service.impl.MesRepairResumeEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/repair-resume-event")
public class MesRepairResumeEventController {
    private final MesRepairResumeEventService service;

    @Inner
    @GetMapping("/internal/evidence")
    public R<?> evidence(@RequestParam Long outboxId, @RequestParam String factoryCode) {
        return R.ok(service.evidence(outboxId, factoryCode));
    }

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<?> page(@RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "20") long size,
                     @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, status));
    }

    @PostMapping("/{id}/retry")
    @SysLog("人工重试维修复工回写PLAN")
    @PreAuthorize("@pms.hasPermission('mesRepairRelease:approve')")
    public R<?> retry(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(service.retry(id, body == null ? null : body.get("requestId")));
    }
}
