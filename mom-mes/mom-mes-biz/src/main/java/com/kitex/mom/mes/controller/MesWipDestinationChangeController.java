package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.service.impl.MesWipDestinationChangeService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWipTransfer")
public class MesWipDestinationChangeController {
    private final MesWipDestinationChangeService service;

    @GetMapping("/{id}/destinationChange")
    @PreAuthorize("@pms.hasPermission('mes_wipTransfer_view')")
    public R view(@PathVariable Long id, @RequestParam(required = false) Long afterId) {
        return R.ok(service.query(id, afterId));
    }
}
