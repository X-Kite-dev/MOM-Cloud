package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.qms.dto.QmsInspectionPieceEntryDTO.*;
import com.kitex.mom.qms.service.impl.QmsInspectionPieceEntryService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/inspection/procedure/pieces/entry-source")
public class QmsInspectionPieceEntryController {
    private final QmsInspectionPieceEntryService service;

    @GetMapping
    @PreAuthorize("@pms.hasPermission('procedureTask:show')")
    public R<View> inspect(@RequestParam String taskId, @RequestParam String pieceId) {
        return R.ok(service.inspect(taskId, pieceId));
    }
}
