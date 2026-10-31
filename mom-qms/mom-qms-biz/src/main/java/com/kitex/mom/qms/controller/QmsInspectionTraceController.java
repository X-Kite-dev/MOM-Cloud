package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.qms.service.impl.QmsInspectionTraceService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/inspection/trace")
@RequiredArgsConstructor
public class QmsInspectionTraceController {
    private final QmsInspectionTraceService trace;

    @GetMapping("/product")
    @PreAuthorize("@pms.hasPermission('productTask:show')")
    public R<QmsInspectionTraceService.View> product(@RequestParam Long taskId) {
        return R.ok(trace.view("PRODUCT", taskId));
    }

    @GetMapping("/procedure")
    @PreAuthorize("@pms.hasPermission('procedureTask:show')")
    public R<QmsInspectionTraceService.View> procedure(@RequestParam Long taskId) {
        return R.ok(trace.view("PROCEDURE", taskId));
    }
}
