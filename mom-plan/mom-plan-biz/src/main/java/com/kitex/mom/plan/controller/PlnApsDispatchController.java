package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.plan.dto.PlnApsMesDispatchDTO;
import com.kitex.mom.plan.service.PlnApsDispatchService;
import com.kitex.mom.plan.task.PlnApsMesDispatchWorker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 智能 APS 到 MES 的下达状态、立即发送和人工重试接口。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/plnApsDispatch")
@Tag(name = "智能APS下达MES")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlnApsDispatchController {

    private final PlnApsDispatchService dispatchService;
    private final PlnApsMesDispatchWorker worker;

    @GetMapping("/case/{caseId}")
    @Operation(summary = "查询APS案件MES下达与逐项回执")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
    public R<PlnApsMesDispatchDTO.DispatchView> detail(@PathVariable Long caseId) {
        return R.ok(dispatchService.detail(caseId));
    }

    @PostMapping("/{outboxId}/process")
    @Operation(summary = "立即处理一条APS到MES下达任务")
    @SysLog("立即处理智能APS下达MES")
    @PreAuthorize("@pms.hasPermission('plan_apsDispatch_retry')")
    public R<PlnApsMesDispatchDTO.DispatchView> process(@PathVariable Long outboxId) {
        return R.ok(worker.process(outboxId));
    }

    @PostMapping("/case/{caseId}/retry")
    @Operation(summary = "人工确认后重新入队失败的MES下达任务")
    @SysLog("重试智能APS下达MES")
    @PreAuthorize("@pms.hasPermission('plan_apsDispatch_retry')")
    public R<PlnApsMesDispatchDTO.DispatchView> retry(
            @PathVariable Long caseId, @RequestBody PlnApsMesDispatchDTO.RetryCommand command) {
        return R.ok(dispatchService.retry(caseId, command == null ? null : command.getReason()));
    }
}
