package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.qms.dto.*;
import com.kitex.mom.qms.service.impl.QmsProcedureDispositionService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/inspection/procedure/disposition")
public class QmsProcedureDispositionController {
    private final QmsProcedureDispositionService service;

    @GetMapping("/{taskId}/basis")
    @PreAuthorize("@pms.hasPermission('procedureTask:handle')")
    public R<QmsProcedureDispositionBasisDTO> basis(@PathVariable Long taskId) {
        return R.ok(service.basis(taskId));
    }

    @GetMapping("/{taskId}/requests/{requestId}")
    @PreAuthorize("@pms.hasPermission('procedureTask:handle')")
    public R<QmsProcedureDispositionReceiptDTO> receipt(
            @PathVariable Long taskId,
            @PathVariable String requestId,
            @RequestParam(required = false) Long expectedEventId,
            @RequestParam(required = false) String expectedFingerprint) {
        return R.ok(service.lookup(taskId, requestId, expectedEventId, expectedFingerprint));
    }
}
