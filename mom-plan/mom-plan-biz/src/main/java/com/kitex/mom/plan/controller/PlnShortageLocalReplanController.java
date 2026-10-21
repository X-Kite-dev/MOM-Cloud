package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.plan.dto.PlanShortageLocalReplanDTO;
import com.kitex.mom.plan.entity.PlnShortageReplanCaseEntity;
import com.kitex.mom.plan.service.PlnShortageLocalReplanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/plnShortageReplan")
@Tag(name = "缺料工序局部重排")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlnShortageLocalReplanController {
    private final PlnShortageLocalReplanService service;

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('plan_shortageReplan_view')")
    public R<Page<PlnShortageReplanCaseEntity>> page(@ParameterObject SearchPage page,
                                                     @ParameterObject PlanShortageLocalReplanDTO.Query query) {
        return R.ok(service.page(page, query));
    }

    @GetMapping("/{caseId}")
    @PreAuthorize("@pms.hasPermission('plan_shortageReplan_view')")
    public R<PlanShortageLocalReplanDTO.Proposal> detail(@PathVariable Long caseId) {
        return R.ok(service.detail(caseId));
    }

    @GetMapping("/scenario/{scenarioId}")
    @PreAuthorize("@pms.hasPermission('plan_shortageReplan_view')")
    public R<PlanShortageLocalReplanDTO.Scenario> scenario(@PathVariable Long scenarioId) {
        return R.ok(service.scenario(scenarioId));
    }

    @PostMapping("/propose")
    @SysLog("生成缺料工序局部重排方案")
    @PreAuthorize("@pms.hasPermission('plan_shortageReplan_propose')")
    public R<PlanShortageLocalReplanDTO.Scenario> propose(
            @RequestBody PlanShortageLocalReplanDTO.ProposeCommand command) {
        return R.ok(service.propose(command));
    }

    @PostMapping("/select")
    @SysLog("选择缺料局部重排候选")
    @PreAuthorize("@pms.hasPermission('plan_shortageReplan_propose')")
    public R<PlanShortageLocalReplanDTO.Scenario> select(
            @RequestBody PlanShortageLocalReplanDTO.SelectCommand command) {
        return R.ok(service.select(command));
    }

    @PostMapping("/publish")
    @SysLog("复核发布缺料工序局部重排")
    @PreAuthorize("@pms.hasPermission('plan_shortageReplan_publish')")
    public R<PlanShortageLocalReplanDTO.PublishReceipt> publish(
            @RequestBody PlanShortageLocalReplanDTO.PublishCommand command) {
        return R.ok(service.publish(command));
    }

    @PostMapping("/cancel")
    @SysLog("取消缺料工序局部重排方案")
    @PreAuthorize("@pms.hasPermission('plan_shortageReplan_propose')")
    public R<Boolean> cancel(@RequestBody PlanShortageLocalReplanDTO.CancelCommand command) {
        return R.ok(service.cancel(command));
    }

    @PostMapping("/rollback")
    @SysLog("受控回退缺料工序局部重排")
    @PreAuthorize("@pms.hasPermission('plan_shortageReplan_rollback')")
    public R<PlanShortageLocalReplanDTO.PublishReceipt> rollback(
            @RequestBody PlanShortageLocalReplanDTO.RollbackCommand command) {
        return R.ok(service.rollback(command));
    }

    @Inner
    @GetMapping("/internal/{caseId}")
    @Operation(summary = "Agent读取不可变局部重排方案")
    public R<PlanShortageLocalReplanDTO.Proposal> internalDetail(@PathVariable Long caseId) {
        return R.ok(service.detail(caseId));
    }

    @PostMapping("/agent/publish")
    @Operation(summary = "执行已审批的A3局部重排发布")
    @PreAuthorize("@pms.hasPermission('agent:approval:execute') and @pms.hasPermission('plan_shortageReplan_publish')")
    public R<PlanShortageLocalReplanDTO.PublishReceipt> agentPublish(
            @RequestBody PlanShortageLocalReplanDTO.PublishCommand command) {
        return R.ok(service.publish(command));
    }
}
