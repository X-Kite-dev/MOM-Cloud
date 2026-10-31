package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesInspectionProductionBasisDTO;
import com.kitex.mom.mes.service.impl.MesInspectionProductionBasisService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/inspection-production-basis/internal")
public class MesInspectionProductionBasisController {
    private final MesInspectionProductionBasisService service;

    @Inner
    @PostMapping("/query")
    public R<MesInspectionProductionBasisDTO.Evidence> query(
            @RequestBody MesInspectionProductionBasisDTO.Query query) {
        return R.ok(service.query(query));
    }
}
