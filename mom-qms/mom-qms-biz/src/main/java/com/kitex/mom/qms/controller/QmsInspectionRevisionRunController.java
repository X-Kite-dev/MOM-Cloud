package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.qms.dto.*;
import com.kitex.mom.qms.service.impl.QmsInspectionRevisionRunEngine;
import com.kitex.mom.qms.service.impl.QmsInspectionRevisionRunService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/inspection/procedure/revisions/{taskId}/{requestId}/execution")
public class QmsInspectionRevisionRunController {
    private final QmsInspectionRevisionRunService service;
    private final QmsInspectionRevisionRunEngine engine;

    @GetMapping
    @PreAuthorize("@pms.hasPermission('procedureTask:history')")
    public R<QmsInspectionRevisionRunDTO> state(
            @PathVariable Long taskId, @PathVariable Long requestId) {
        return R.ok(service.state(taskId, requestId));
    }

    @PostMapping
    @PreAuthorize("@pms.hasPermission('procedureTask:revision:execute')")
    @SysLog("执行受控质检修订")
    public R<QmsInspectionRevisionRunDTO> execute(
            @PathVariable Long taskId, @PathVariable Long requestId) {
        service.start(taskId, requestId);
        engine.advance(requestId);
        return R.ok(service.state(taskId, requestId));
    }

    @PostMapping("/abort")
    @PreAuthorize("@pms.hasPermission('procedureTask:revision:abort')")
    @SysLog("中止未提交质检修订")
    public R<QmsInspectionRevisionRunDTO> abort(
            @PathVariable Long taskId,
            @PathVariable Long requestId,
            @RequestBody QmsInspectionRevisionCancelCommand command) {
        service.abort(taskId, requestId, command);
        engine.advance(requestId);
        return R.ok(service.state(taskId, requestId));
    }

    @PostMapping("/retry")
    @PreAuthorize("@pms.hasPermission('procedureTask:revision:retry')")
    @SysLog("恢复正式质检修订")
    public R<QmsInspectionRevisionRunDTO> retry(
            @PathVariable Long taskId, @PathVariable Long requestId) {
        service.retry(taskId, requestId);
        engine.advance(requestId);
        return R.ok(service.state(taskId, requestId));
    }
}
