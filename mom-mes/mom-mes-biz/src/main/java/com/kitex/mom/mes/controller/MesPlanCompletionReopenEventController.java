package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.service.impl.MesPlanCompletionReopenEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * MES完工重新打开向PLAN可靠回写的查询、核验和死信续投入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/plan-completion-reopen")
public class MesPlanCompletionReopenEventController {

    private final MesPlanCompletionReopenEventService service;

    @Inner
    @GetMapping("/internal/evidence")
    public R<?> evidence(@RequestParam Long outboxId, @RequestParam String factoryCode) {
        return R.ok(service.evidence(outboxId, factoryCode));
    }

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('mesTaskReportReverse:view')")
    public R<?> page(@RequestParam(defaultValue = "1") long current,
                     @RequestParam(defaultValue = "20") long size,
                     @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, status));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mesTaskReportReverse:view')")
    public R<?> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/{id}/retry")
    @SysLog("人工续投MES完工重新打开事件")
    @PreAuthorize("@pms.hasPermission('mesTaskReportReverse:review')")
    public R<?> retry(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(service.retry(id, body == null ? null : body.get("requestId")));
    }
}
