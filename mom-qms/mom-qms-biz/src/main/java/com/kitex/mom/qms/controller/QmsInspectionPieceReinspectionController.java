package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.qms.dto.QmsInspectionPieceReinspectionDTO.*;
import com.kitex.mom.qms.service.impl.QmsInspectionPieceReinspectionService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/inspection/procedure/pieces/reinspection")
public class QmsInspectionPieceReinspectionController {
    private final QmsInspectionPieceReinspectionService service;

    @GetMapping
    @PreAuthorize("@pms.hasPermission('procedureTask:show')")
    public R<View> inspect(@RequestParam String taskId, @RequestParam String pieceId) {
        return R.ok(service.inspect(taskId, pieceId));
    }

    @PostMapping
    @PreAuthorize("@pms.hasPermission('procedureTask:submit')")
    public R<View> bind(@RequestBody Command command) {
        return R.ok(service.bind(command));
    }
}
