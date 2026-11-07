package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesDeliveryTargetDTO.Choice;
import com.kitex.mom.mes.service.impl.MesDeliveryTargetService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWipTransfer/targetLocation")
@PreAuthorize("@pms.hasPermission('mes_wipTransfer_view')")
public class MesDeliveryTargetController {
    private final MesDeliveryTargetService service;

    @GetMapping("/{transfer}/options")
    public R options(
            @PathVariable String transfer,
            @RequestParam String arrangeId,
            @RequestParam String worksiteCode) {
        return R.ok(service.options(transfer, arrangeId, worksiteCode));
    }

    @PostMapping("/check")
    public R check(@RequestBody Choice c) {
        return R.ok(service.check(c));
    }
}
