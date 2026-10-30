package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesShiftLeftoverCommand;
import com.kitex.mom.mes.service.impl.MesShiftLeftoverService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 班次遗留责任入口；按钮权限之外，服务逐次核身份/工厂/原责任及独立验证。
 */
@RestController
@RequestMapping("/shift-handover/leftovers")
@RequiredArgsConstructor
public class MesShiftLeftoverController {
    private final MesShiftLeftoverService service;

    @GetMapping
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<?> page(@RequestParam(required = false) Long handoverId,
                     @RequestParam(defaultValue = "1") long current,
                     @RequestParam(required = false) String status) {
        return R.ok(service.page(handoverId, current, status));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<?> history(@PathVariable Long id, @RequestParam(defaultValue = "1") long current) {
        return R.ok(service.history(id, current));
    }

    @GetMapping("/request/{requestId}")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<?> request(@PathVariable String requestId) {
        return R.ok(service.request(requestId));
    }

    @PostMapping("/request/{requestId}/cancel-unexecuted")
    @PreAuthorize("@pms.hasPermission('mesShiftHandover:submit', 'mesShiftHandover:receive')")
    public R<?> cancelUnexecuted(@PathVariable String requestId) {
        return R.ok(service.cancelUnexecuted(requestId));
    }

    @PostMapping("/act/{action}")
    @PreAuthorize("@pms.hasPermission(#action == 'REASSIGN' ? 'mesShiftHandover:submit' : 'mesShiftHandover:receive')")
    public R<?> act(@PathVariable String action, @RequestBody MesShiftLeftoverCommand command) {
        return R.ok(service.act(action, command));
    }
}
