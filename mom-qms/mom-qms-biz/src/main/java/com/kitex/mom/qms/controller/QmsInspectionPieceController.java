package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.qms.dto.QmsInspectionPieceDTO.*;
import com.kitex.mom.qms.service.InspectionProcedureTaskService;
import com.kitex.mom.qms.service.impl.QmsInspectionPieceService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/inspection/procedure/pieces")
public class QmsInspectionPieceController {
    private final InspectionProcedureTaskService tasks;
    private final QmsInspectionPieceService pieces;

    @GetMapping
    @PreAuthorize("@pms.hasPermission('procedureTask:show')")
    public R<View> view(@RequestParam String taskId, @RequestParam(defaultValue = "1") int page) {
        return R.ok(pieces.view(taskId, page));
    }

    @GetMapping("/request")
    @PreAuthorize("@pms.hasPermission('procedureTask:show')")
    public R<Receipt> request(@RequestParam String taskId, @RequestParam String requestId) {
        return R.ok(pieces.request(taskId, requestId));
    }

    @PostMapping
    @PreAuthorize("@pms.hasPermission('procedureTask:submit')")
    public R<Receipt> collect(@RequestBody Command command) {
        return R.ok(tasks.collectPiece(command));
    }

    @PostMapping("/cancel")
    @PreAuthorize("@pms.hasPermission('procedureTask:submit')")
    public R<Receipt> cancel(@RequestBody Command command) {
        return R.ok(pieces.cancel(command));
    }
}
