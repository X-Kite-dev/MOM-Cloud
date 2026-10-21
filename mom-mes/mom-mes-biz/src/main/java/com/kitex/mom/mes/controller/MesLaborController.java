package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesLaborCommand;
import com.kitex.mom.mes.service.impl.MesLaborService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/labor-entry")
public class MesLaborController {
    private final MesLaborService service;

    @GetMapping("/context")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<?> context(@RequestParam String arrangeCode, @RequestParam(defaultValue = "1") long page) {
        return R.ok(service.context(arrangeCode, page));
    }

    @GetMapping("/preview/{segmentId}")
    @PreAuthorize("@pms.hasPermission('mesLabor:submit')")
    public R<?> preview(@PathVariable Long segmentId) {
        return R.ok(service.preview(segmentId));
    }

    @GetMapping
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<?> page(@RequestParam(defaultValue = "1") long page, @RequestParam(required = false) String status) {
        return R.ok(service.page(page, status));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show')")
    public R<?> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/{action}")
    @PreAuthorize("@pms.hasPermission(#action == 'SUBMIT' || #action == 'WITHDRAW' || #action == 'REQUEST_VOID' || #action == 'WITHDRAW_VOID' ? 'mesLabor:submit' : 'mesLabor:review')")
    public R<?> execute(@PathVariable String action, @RequestBody MesLaborCommand c) {
        return R.ok(service.execute(action, c));
    }
}
