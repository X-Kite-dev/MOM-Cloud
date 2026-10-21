package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.common.security.annotation.Inner;
import com.kitex.mom.plan.dto.PlanAutomationEventDTO;
import com.kitex.mom.plan.entity.PlnAutomationEventEntity;
import com.kitex.mom.plan.entity.PlnAutomationEventPolicyEntity;
import com.kitex.mom.plan.service.PlnAutomationEventService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/plnAutomationEvent")
@Tag(name = "制造异常事件订阅与智能排产自动触发")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlnAutomationEventController {
    private final PlnAutomationEventService service;

    @Inner
    @PostMapping("/receive")
    public R<PlanAutomationEventDTO.ReceiveReceipt> receive(@RequestBody PlanAutomationEventDTO.ReceiveCommand c) {
        return R.ok(service.receive(c));
    }

    @GetMapping("/page")
    @PreAuthorize("@pms.hasPermission('plan_automationEvent_view')")
    public R<Page<PlnAutomationEventEntity>> page(@ParameterObject SearchPage page,
                                                  @ParameterObject PlanAutomationEventDTO.EventQuery query) {
        return R.ok(service.eventPage(page, query));
    }

    @GetMapping("/{eventInboxId}")
    @PreAuthorize("@pms.hasPermission('plan_automationEvent_view')")
    public R<PlanAutomationEventDTO.EventDetail> detail(@PathVariable Long eventInboxId) {
        return R.ok(service.detail(eventInboxId));
    }

    @GetMapping("/policy/page")
    @PreAuthorize("@pms.hasPermission('plan_automationEvent_policy')")
    public R<Page<PlnAutomationEventPolicyEntity>> policyPage(@ParameterObject SearchPage page,
                                                              @ParameterObject PlanAutomationEventDTO.PolicyQuery query) {
        return R.ok(service.policyPage(page, query));
    }

    @PostMapping("/policy/draft")
    @SysLog("保存事件订阅策略草稿")
    @PreAuthorize("@pms.hasPermission('plan_automationEvent_policy')")
    public R<PlnAutomationEventPolicyEntity> savePolicyDraft(@RequestBody PlanAutomationEventDTO.PolicyDraftCommand c) {
        return R.ok(service.savePolicyDraft(c));
    }

    @PostMapping("/policy/activate")
    @SysLog("复核启用事件订阅策略")
    @PreAuthorize("@pms.hasPermission('plan_automationEvent_policyApprove')")
    public R<PlnAutomationEventPolicyEntity> activatePolicy(@RequestBody PlanAutomationEventDTO.PolicyActionCommand c) {
        return R.ok(service.activatePolicy(c));
    }

    @PostMapping("/policy/retire")
    @SysLog("退役事件订阅策略")
    @PreAuthorize("@pms.hasPermission('plan_automationEvent_policyApprove')")
    public R<PlnAutomationEventPolicyEntity> retirePolicy(@RequestBody PlanAutomationEventDTO.PolicyActionCommand c) {
        return R.ok(service.retirePolicy(c));
    }

    @PostMapping("/retry")
    @SysLog("人工重试制造异常事件")
    @PreAuthorize("@pms.hasPermission('plan_automationEvent_handle')")
    public R<Boolean> retry(@RequestBody PlanAutomationEventDTO.ManualActionCommand c) {
        return R.ok(service.retry(c));
    }

    @PostMapping("/ignore")
    @SysLog("人工忽略制造异常事件")
    @PreAuthorize("@pms.hasPermission('plan_automationEvent_handle')")
    public R<Boolean> ignore(@RequestBody PlanAutomationEventDTO.ManualActionCommand c) {
        return R.ok(service.ignore(c));
    }
}
