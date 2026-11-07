package com.kitex.mom.mes.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.mes.dto.MesInspectionPieceEntryDTO.*;
import com.kitex.mom.mes.service.impl.MesInspectionPieceEntryService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/inspection-piece-entry/internal")
public class MesInspectionPieceEntryController {
    private final MesInspectionPieceEntryService service;

    @Inner
    @PostMapping("/query")
    public R<View> query(@RequestBody Query query) {
        return R.ok(service.query(query));
    }
}
