package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.mes.dto.MesWipResidenceQuery;
import com.kitex.mom.mes.service.impl.MesWipResidenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/mesWipResidence")
public class MesWipResidenceController {
    private final MesWipResidenceService service;

    @GetMapping
    public R<?> view(MesWipResidenceQuery query) {
        return R.ok(service.view(query));
    }

    @GetMapping("/{stockId}/incidents")
    public R<?> incidents(@PathVariable Long stockId, @RequestParam(defaultValue = "1") long current) {
        return R.ok(service.incidents(stockId, current));
    }
}
