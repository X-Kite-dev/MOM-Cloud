package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.plan.dto.PlanInsertionLocalReplanDTO;
import com.kitex.mom.plan.entity.PlnInsertionReplanCaseEntity;
import com.kitex.mom.plan.entity.PlnInsertionReplanPolicyEntity;
import com.kitex.mom.plan.service.PlnInsertionLocalReplanService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/plnInsertionReplan")
@Tag(name = "紧急插单影响评估与联合局部重排")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlnInsertionLocalReplanController {
    private final PlnInsertionLocalReplanService service;

    @GetMapping("/policy/page")
    @PreAuthorize("@pms.hasPermission('plan_insertionReplan_policy')")
    public R<Page<PlnInsertionReplanPolicyEntity>> policyPage(@ParameterObject SearchPage page,
                                                              @ParameterObject PlanInsertionLocalReplanDTO.PolicyQuery query) {
        return R.ok(service.policyPage(page, query));
    }

    @PostMapping("/policy/draft")
    @SysLog("保存紧急插单策略草稿")
    @PreAuthorize("@pms.hasPermission('plan_insertionReplan_policy')")
    public R<PlnInsertionReplanPolicyEntity> savePolicyDraft(@RequestBody PlanInsertionLocalReplanDTO.PolicyDraftCommand c) {
        return R.ok(service.savePolicyDraft(c));
    }

    @PostMapping("/policy/activate")
    @SysLog("复核启用紧急插单策略")
    @PreAuthorize("@pms.hasPermission('plan_insertionReplan_policyApprove')")
    public R<PlnInsertionReplanPolicyEntity> activatePolicy(@RequestBody PlanInsertionLocalReplanDTO.PolicyActionCommand c) {
        return R.ok(service.activatePolicy(c));
    }

    @PostMapping("/policy/retire")
    @SysLog("退役紧急插单策略")
    @PreAuthorize("@pms.hasPermission('plan_insertionReplan_policyApprove')")
    public R<PlnInsertionReplanPolicyEntity> retirePolicy(@RequestBody PlanInsertionLocalReplanDTO.PolicyActionCommand c) {
        return R.ok(service.retirePolicy(c));
    }

    @GetMapping("/case/page")
    @PreAuthorize("@pms.hasPermission('plan_insertionReplan_view')")
    public R<Page<PlnInsertionReplanCaseEntity>> casePage(@ParameterObject SearchPage page,
                                                          @ParameterObject PlanInsertionLocalReplanDTO.CaseQuery query) {
        return R.ok(service.casePage(page, query));
    }

    @GetMapping("/case/{caseId}")
    @PreAuthorize("@pms.hasPermission('plan_insertionReplan_view')")
    public R<PlanInsertionLocalReplanDTO.CaseDetail> detail(@PathVariable Long caseId) {
        return R.ok(service.detail(caseId));
    }

    @GetMapping("/scenario/{scenarioId}")
    @PreAuthorize("@pms.hasPermission('plan_insertionReplan_view')")
    public R<PlanInsertionLocalReplanDTO.Scenario> scenario(@PathVariable Long scenarioId) {
        return R.ok(service.scenario(scenarioId));
    }

    @PostMapping("/assess")
    @SysLog("评估紧急插单并生成联合重排候选")
    @PreAuthorize("@pms.hasPermission('plan_insertionReplan_assess')")
    public R<PlanInsertionLocalReplanDTO.Scenario> assess(@RequestBody PlanInsertionLocalReplanDTO.AssessCommand c) {
        return R.ok(service.assess(c));
    }

    @PostMapping("/select")
    @SysLog("选择紧急插单联合重排候选")
    @PreAuthorize("@pms.hasPermission('plan_insertionReplan_assess')")
    public R<PlanInsertionLocalReplanDTO.Scenario> select(@RequestBody PlanInsertionLocalReplanDTO.SelectCommand c) {
        return R.ok(service.select(c));
    }

    @PostMapping("/publish")
    @SysLog("复核发布紧急插单联合重排")
    @PreAuthorize("@pms.hasPermission('plan_insertionReplan_publish')")
    public R<PlanInsertionLocalReplanDTO.PublishReceipt> publish(@RequestBody PlanInsertionLocalReplanDTO.PublishCommand c) {
        return R.ok(service.publish(c));
    }

    @PostMapping("/cancel")
    @SysLog("取消紧急插单候选")
    @PreAuthorize("@pms.hasPermission('plan_insertionReplan_assess')")
    public R<Boolean> cancel(@RequestBody PlanInsertionLocalReplanDTO.CancelCommand c) {
        return R.ok(service.cancel(c));
    }

    @PostMapping("/rollback")
    @SysLog("受控回退紧急插单联合重排")
    @PreAuthorize("@pms.hasPermission('plan_insertionReplan_rollback')")
    public R<PlanInsertionLocalReplanDTO.PublishReceipt> rollback(@RequestBody PlanInsertionLocalReplanDTO.RollbackCommand c) {
        return R.ok(service.rollback(c));
    }
}
