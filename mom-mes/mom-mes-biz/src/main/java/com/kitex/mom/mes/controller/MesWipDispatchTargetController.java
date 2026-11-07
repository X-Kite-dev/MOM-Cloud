package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.service.impl.MesWipDispatchTargetService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWipTransfer/dispatchTarget")
@PreAuthorize("@pms.hasPermission('mes_wipTransfer_dispatch')")
public class MesWipDispatchTargetController {
    private final MesWipDispatchTargetService service;

    @GetMapping("/{stockId}/options")
    public R options(
            @PathVariable String stockId,
            @RequestParam String arrangeId,
            @RequestParam String worksiteCode) {
        return R.ok(service.options(stockId, arrangeId, worksiteCode));
    }
}
