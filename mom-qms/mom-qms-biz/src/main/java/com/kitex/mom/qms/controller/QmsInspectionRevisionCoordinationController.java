package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.qms.dto.QmsInspectionRevisionCancelCommand;
import com.kitex.mom.qms.dto.QmsInspectionRevisionCoordinationDTO;
import com.kitex.mom.qms.service.impl.QmsInspectionRevisionCoordinationService;
import com.kitex.mom.qms.service.impl.QmsInspectionRevisionCoordinationWorker;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 准备/取消原件已提交后才尝试投递；未知可由同件后台补偿恢复。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/inspection/procedure/revisions/{taskId}/{requestId}/coordination")
public class QmsInspectionRevisionCoordinationController {
    private final QmsInspectionRevisionCoordinationService service;
    private final QmsInspectionRevisionCoordinationWorker worker;

    @GetMapping
    @PreAuthorize("@pms.hasPermission('procedureTask:history')")
    public R<QmsInspectionRevisionCoordinationDTO> state(
            @PathVariable Long taskId, @PathVariable Long requestId) {
        return R.ok(service.state(taskId, requestId));
    }

    @PostMapping("/prepare")
    @PreAuthorize("@pms.hasPermission('procedureTask:revision:prepare')")
    @SysLog("准备受控质检修订")
    public R<QmsInspectionRevisionCoordinationDTO> prepare(
            @PathVariable Long taskId, @PathVariable Long requestId) {
        service.prepare(taskId, requestId);
        worker.deliver(requestId);
        return R.ok(service.state(taskId, requestId));
    }

    @PostMapping("/cancel")
    @PreAuthorize("@pms.hasPermission('procedureTask:revision:cancel')")
    @SysLog("取消受控质检修订准备")
    public R<QmsInspectionRevisionCoordinationDTO> cancel(
            @PathVariable Long taskId,
            @PathVariable Long requestId,
            @RequestBody QmsInspectionRevisionCancelCommand command) {
        service.cancel(taskId, requestId, command);
        worker.deliver(requestId);
        return R.ok(service.state(taskId, requestId));
    }

    @PostMapping("/retry")
    @PreAuthorize("@pms.hasPermission('procedureTask:revision:retry')")
    @SysLog("按原件重排受控质检修订协调")
    public R<QmsInspectionRevisionCoordinationDTO> retry(
            @PathVariable Long taskId, @PathVariable Long requestId) {
        service.retry(taskId, requestId);
        worker.deliver(requestId);
        return R.ok(service.state(taskId, requestId));
    }
}
