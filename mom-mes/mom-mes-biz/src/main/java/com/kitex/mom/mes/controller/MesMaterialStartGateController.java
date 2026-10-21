package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.service.impl.MesMaterialStartGateService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesTask/arrange/material-readiness")
public class MesMaterialStartGateController {
    private final MesMaterialStartGateService service;

    @GetMapping("/{id}")
    @PreAuthorize("@pms.hasPermission('mesTaskArrange:show','mesProduceTask:start')")
    public R<?> preview(@PathVariable Long id, @RequestParam(defaultValue = "1") long page) {
        return R.ok(service.preview(id, page));
    }
}
