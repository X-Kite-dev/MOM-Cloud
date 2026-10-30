package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesShiftHandoverCommand;
import com.kitex.mom.mes.service.impl.MesFormalHandoverSourceService;
import com.kitex.mom.mes.service.impl.MesShiftHandoverService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/shift-handover")
public class MesShiftHandoverController {
    private final MesShiftHandoverService service;
    private final MesFormalHandoverSourceService formal;

    @GetMapping("/shifts")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<?> shifts() {
        return R.ok(formal.shifts());
    }

    @GetMapping("/participants")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<?> participants(@RequestParam(required = false) String keyword,
                             @RequestParam(defaultValue = "1") long current,
                             @RequestParam(defaultValue = "10") long size) {
        return R.ok(formal.participants(keyword, current, size));
    }

    @GetMapping("/request/{requestId}")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<?> request(@PathVariable String requestId) {
        return R.ok(service.requestReceipt(requestId));
    }

    @PostMapping("/request/{requestId}/cancel-unexecuted")
    @PreAuthorize("@pms.hasPermission('mesShiftHandover:submit', 'mesShiftHandover:receive')")
    public R<?> cancelUnexecuted(@PathVariable String requestId) {
        return R.ok(service.cancelUnexecuted(requestId));
    }

    @PostMapping("/preview")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<?> preview(@RequestBody MesShiftHandoverCommand c) {
        return R.ok(service.preview(c));
    }

    @GetMapping
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<?> page(@RequestParam(defaultValue = "1") long page) {
        return R.ok(service.page(page));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<?> detail(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean recheck) {
        return R.ok(service.detail(id, recheck));
    }

    @PostMapping("/act/{action}")
    @PreAuthorize("@pms.hasPermission(#action == 'SUBMIT' || #action == 'WITHDRAW' ? 'mesShiftHandover:submit' : 'mesShiftHandover:receive')")
    public R<?> execute(@PathVariable String action, @RequestBody MesShiftHandoverCommand c) {
        return R.ok(service.execute(action, c));
    }
}
