package com.kitex.mom.qms.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.qms.dto.*;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.service.impl.QmsInspectionRevisionService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 独立申请/审核权限；不暴露改判、执行或解除冲突接口。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/inspection/procedure/revisions")
public class QmsInspectionRevisionController {
    private final QmsInspectionRevisionService service;

    @GetMapping("/{taskId}/basis")
    @PreAuthorize("@pms.hasPermission('procedureTask:history')")
    public R<QmsInspectionRevisionService.Basis> basis(@PathVariable Long taskId) {
        return R.ok(service.basis(taskId));
    }

    @GetMapping("/{taskId}")
    @PreAuthorize("@pms.hasPermission('procedureTask:history')")
    public R<QmsInspectionRevisionService.Page> page(
            @PathVariable Long taskId,
            @RequestParam(defaultValue = "0") Long after,
            @RequestParam(defaultValue = "20") int limit) {
        return R.ok(service.page(taskId, after, limit));
    }

    @GetMapping("/{taskId}/{requestId}")
    @PreAuthorize("@pms.hasPermission('procedureTask:history')")
    public R<QmsInspectionRevisionService.Detail> detail(
            @PathVariable Long taskId, @PathVariable Long requestId) {
        return R.ok(service.detail(taskId, requestId));
    }

    @PostMapping
    @PreAuthorize("@pms.hasPermission('procedureTask:revision:apply')")
    @SysLog("申请受控质检修订")
    public R<QmsInspectionRevisionEntity> apply(@RequestBody QmsInspectionRevisionApply command) {
        return R.ok(service.apply(command));
    }

    @PostMapping("/{taskId}/{requestId}/review")
    @PreAuthorize("@pms.hasPermission('procedureTask:revision:review')")
    @SysLog("审核受控质检修订")
    public R<QmsInspectionRevisionReviewEntity> review(
            @PathVariable Long taskId,
            @PathVariable Long requestId,
            @RequestBody QmsInspectionRevisionReview command) {
        return R.ok(service.review(taskId, requestId, command));
    }
}
