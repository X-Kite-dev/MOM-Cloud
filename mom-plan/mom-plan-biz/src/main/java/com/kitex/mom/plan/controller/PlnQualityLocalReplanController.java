package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.plan.dto.PlanQualityLocalReplanDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.service.PlnQualityLocalReplanService;
import com.kitex.mom.plan.service.PlnQualitySupplementWithdrawalService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/plnQualityReplan")
@Tag(name = "质量偏差阈值与局部重排")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlnQualityLocalReplanController {
    private final PlnQualityLocalReplanService service;
    private final PlnQualitySupplementWithdrawalService withdrawalService;

    @GetMapping("/policy/page")
    @PreAuthorize("@pms.hasPermission('plan_qualityReplan_policy')")
    public R<Page<PlnQualityReplanPolicyEntity>> policyPage(@ParameterObject SearchPage page,
                                                            @ParameterObject PlanQualityLocalReplanDTO.PolicyQuery query) {
        return R.ok(service.policyPage(page, query));
    }

    @PostMapping("/policy/draft")
    @SysLog("保存质量偏差重排策略草稿")
    @PreAuthorize("@pms.hasPermission('plan_qualityReplan_policy')")
    public R<PlnQualityReplanPolicyEntity> savePolicyDraft(
            @RequestBody PlanQualityLocalReplanDTO.PolicyDraftCommand command) {
        return R.ok(service.savePolicyDraft(command));
    }

    @PostMapping("/policy/activate")
    @SysLog("复核启用质量偏差重排策略")
    @PreAuthorize("@pms.hasPermission('plan_qualityReplan_policyApprove')")
    public R<PlnQualityReplanPolicyEntity> activatePolicy(
            @RequestBody PlanQualityLocalReplanDTO.PolicyActionCommand command) {
        return R.ok(service.activatePolicy(command));
    }

    @PostMapping("/policy/retire")
    @SysLog("退役质量偏差重排策略")
    @PreAuthorize("@pms.hasPermission('plan_qualityReplan_policyApprove')")
    public R<PlnQualityReplanPolicyEntity> retirePolicy(
            @RequestBody PlanQualityLocalReplanDTO.PolicyActionCommand command) {
        return R.ok(service.retirePolicy(command));
    }

    @GetMapping("/evaluation/page")
    @PreAuthorize("@pms.hasPermission('plan_qualityReplan_view')")
    public R<Page<PlnQualityDeviationEvaluationEntity>> evaluationPage(@ParameterObject SearchPage page,
                                                                       @ParameterObject PlanQualityLocalReplanDTO.EvaluationQuery query) {
        return R.ok(service.evaluationPage(page, query));
    }

    @PostMapping("/evaluation/reevaluate")
    @SysLog("重新评估计划质量偏差阈值")
    @PreAuthorize("@pms.hasPermission('plan_qualityReplan_assess')")
    public R<PlnQualityDeviationEvaluationEntity> reevaluate(
            @RequestBody PlanQualityLocalReplanDTO.ReevaluateCommand command) {
        return R.ok(service.reevaluate(command));
    }

    @GetMapping("/case/page")
    @PreAuthorize("@pms.hasPermission('plan_qualityReplan_view')")
    public R<Page<PlnQualityReplanCaseEntity>> casePage(@ParameterObject SearchPage page,
                                                        @ParameterObject PlanQualityLocalReplanDTO.CaseQuery query) {
        return R.ok(service.casePage(page, query));
    }

    @GetMapping("/case/{caseId}")
    @PreAuthorize("@pms.hasPermission('plan_qualityReplan_view')")
    public R<PlanQualityLocalReplanDTO.CaseDetail> detail(@PathVariable Long caseId) {
        return R.ok(service.detail(caseId));
    }

    @GetMapping("/scenario/{scenarioId}")
    @PreAuthorize("@pms.hasPermission('plan_qualityReplan_view')")
    public R<PlanQualityLocalReplanDTO.Scenario> scenario(@PathVariable Long scenarioId) {
        return R.ok(service.scenario(scenarioId));
    }

    @GetMapping("/supplement/page")
    @PreAuthorize("@pms.hasPermission('plan_qualitySupplement_view')")
    public R<Page<PlnQualitySupplementDemandEntity>> supplementPage(
            @ParameterObject SearchPage page,
            @ParameterObject PlanQualityLocalReplanDTO.SupplementQuery query) {
        return R.ok(service.supplementPage(page, query));
    }

    @PostMapping("/supplement/submit")
    @SysLog("提交质量报废补产需求")
    @PreAuthorize("@pms.hasPermission('plan_qualitySupplement_submit')")
    public R<PlnQualitySupplementDemandEntity> submitSupplement(
            @RequestBody PlanQualityLocalReplanDTO.SupplementSubmitCommand command) {
        return R.ok(service.submitSupplement(command));
    }

    @PostMapping("/supplement/review")
    @SysLog("复核质量报废补产需求并生成MRP")
    @PreAuthorize("@pms.hasPermission('plan_qualitySupplement_review')")
    public R<PlnQualitySupplementDemandEntity> reviewSupplement(
            @RequestBody PlanQualityLocalReplanDTO.SupplementReviewCommand command) {
        return R.ok(service.reviewSupplement(command));
    }

    @PostMapping("/supplement/cancel")
    @SysLog("取消待复核质量报废补产需求")
    @PreAuthorize("@pms.hasPermission('plan_qualitySupplement_submit')")
    public R<PlnQualitySupplementDemandEntity> cancelSupplement(
            @RequestBody PlanQualityLocalReplanDTO.SupplementCancelCommand command) {
        return R.ok(service.cancelSupplement(command));
    }

    @PostMapping("/supplement/withdrawal/submit")
    @SysLog("提交质量补产下游受控撤回")
    @PreAuthorize("@pms.hasPermission('plan_qualitySupplement_withdraw')")
    public R<PlnQualitySupplementWithdrawalEntity> submitWithdrawal(
            @RequestBody PlanQualityLocalReplanDTO.SupplementWithdrawalSubmitCommand command) {
        return R.ok(withdrawalService.submit(command));
    }

    @PostMapping("/supplement/withdrawal/review")
    @SysLog("复核并执行质量补产下游受控撤回")
    @PreAuthorize("@pms.hasPermission('plan_qualitySupplement_withdrawReview')")
    public R<PlnQualitySupplementWithdrawalEntity> reviewWithdrawal(
            @RequestBody PlanQualityLocalReplanDTO.SupplementWithdrawalReviewCommand command) {
        return R.ok(withdrawalService.review(command));
    }

    @PostMapping("/supplement/withdrawal/retry")
    @SysLog("重试质量补产下游受控撤回")
    @PreAuthorize("@pms.hasPermission('plan_qualitySupplement_withdrawReview')")
    public R<PlnQualitySupplementWithdrawalEntity> retryWithdrawal(
            @RequestBody PlanQualityLocalReplanDTO.SupplementWithdrawalRetryCommand command) {
        return R.ok(withdrawalService.retry(command));
    }

    @PostMapping("/assess")
    @SysLog("评估并生成质量偏差局部重排方案")
    @PreAuthorize("@pms.hasPermission('plan_qualityReplan_assess')")
    public R<PlanQualityLocalReplanDTO.Scenario> assess(
            @RequestBody PlanQualityLocalReplanDTO.AssessCommand command) {
        return R.ok(service.assess(command));
    }

    @PostMapping("/select")
    @SysLog("选择质量偏差局部重排候选")
    @PreAuthorize("@pms.hasPermission('plan_qualityReplan_assess')")
    public R<PlanQualityLocalReplanDTO.Scenario> select(
            @RequestBody PlanQualityLocalReplanDTO.SelectCommand command) {
        return R.ok(service.select(command));
    }

    @PostMapping("/publish")
    @SysLog("复核发布质量偏差局部重排")
    @PreAuthorize("@pms.hasPermission('plan_qualityReplan_publish')")
    public R<PlanQualityLocalReplanDTO.PublishReceipt> publish(
            @RequestBody PlanQualityLocalReplanDTO.PublishCommand command) {
        return R.ok(service.publish(command));
    }

    @PostMapping("/cancel")
    @SysLog("取消质量偏差局部重排案件")
    @PreAuthorize("@pms.hasPermission('plan_qualityReplan_assess')")
    public R<Boolean> cancel(@RequestBody PlanQualityLocalReplanDTO.CancelCommand command) {
        return R.ok(service.cancel(command));
    }

    @PostMapping("/rollback")
    @SysLog("受控回退质量偏差局部重排")
    @PreAuthorize("@pms.hasPermission('plan_qualityReplan_rollback')")
    public R<PlanQualityLocalReplanDTO.PublishReceipt> rollback(
            @RequestBody PlanQualityLocalReplanDTO.RollbackCommand command) {
        return R.ok(service.rollback(command));
    }
}
