package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.plan.dto.PlanResourceLocalReplanDTO;
import com.kitex.mom.plan.dto.PlnMpsPublicationRecoveryDTO;
import com.kitex.mom.plan.entity.PlnRepairResumeObservation;
import com.kitex.mom.plan.entity.PlnResourceReplanCaseEntity;
import com.kitex.mom.plan.entity.PlnResourceReplanOperationEntity;
import com.kitex.mom.plan.entity.PlnResourceReplanPolicyEntity;
import com.kitex.mom.plan.service.PlnResourceLocalReplanService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/plnResourceReplan")
@Tag(name = "设备工装人员异常局部重排")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlnResourceLocalReplanController {
    private final PlnResourceLocalReplanService service;

    @GetMapping("/policy/page")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_policy')")
    public R<Page<PlnResourceReplanPolicyEntity>> policyPage(
            @ParameterObject SearchPage page, @ParameterObject PlanResourceLocalReplanDTO.PolicyQuery query) {
        return R.ok(service.policyPage(page, query));
    }

    @PostMapping("/policy/draft")
    @SysLog("保存资源异常重排策略草稿")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_policy')")
    public R<PlnResourceReplanPolicyEntity> savePolicyDraft(
            @RequestBody PlanResourceLocalReplanDTO.PolicyDraftCommand command) {
        return R.ok(service.savePolicyDraft(command));
    }

    @PostMapping("/policy/activate")
    @SysLog("复核启用资源异常重排策略")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_policyApprove')")
    public R<PlnResourceReplanPolicyEntity> activatePolicy(
            @RequestBody PlanResourceLocalReplanDTO.PolicyActionCommand command) {
        return R.ok(service.activatePolicy(command));
    }

    @PostMapping("/policy/retire")
    @SysLog("退役资源异常重排策略")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_policyApprove')")
    public R<PlnResourceReplanPolicyEntity> retirePolicy(
            @RequestBody PlanResourceLocalReplanDTO.PolicyActionCommand command) {
        return R.ok(service.retirePolicy(command));
    }

    @GetMapping("/case/page")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_view')")
    public R<Page<PlnResourceReplanCaseEntity>> casePage(
            @ParameterObject SearchPage page, @ParameterObject PlanResourceLocalReplanDTO.CaseQuery query) {
        return R.ok(service.casePage(page, query));
    }

    @GetMapping("/case/{caseId}")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_view')")
    public R<PlanResourceLocalReplanDTO.CaseDetail> detail(@PathVariable Long caseId) {
        return R.ok(service.detail(caseId));
    }

    @GetMapping("/scenario/{scenarioId}")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_view')")
    public R<PlanResourceLocalReplanDTO.Scenario> scenario(@PathVariable Long scenarioId) {
        return R.ok(service.scenario(scenarioId));
    }

    /**
     * 为MomAI受控动作提供固定POST查询契约。
     *
     * <p>只接受情景主键，租户与工厂范围仍由服务层按当前身份强制校验。</p>
     */
    @PostMapping("/scenario/query")
    @PreAuthorize("@pms.hasPermission('agent:run:view')")
    public R<PlanResourceLocalReplanDTO.Scenario> scenarioQuery(
            @RequestBody PlanResourceLocalReplanDTO.ScenarioQueryCommand command) {
        if (command == null || command.getScenarioId() == null) {
            throw new IllegalArgumentException("局部重排情景ID不能为空");
        }
        return R.ok(service.scenario(command.getScenarioId()));
    }

    @PostMapping("/assess")
    @SysLog("评估资源异常并生成局部重排候选")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_assess')")
    public R<PlanResourceLocalReplanDTO.Scenario> assess(
            @RequestBody PlanResourceLocalReplanDTO.AssessCommand command) {
        return R.ok(service.assess(command));
    }

    @PostMapping("/execution-deviation/assess")
    @SysLog("评估MES执行偏差并生成局部重排候选")
    @PreAuthorize("@pms.hasPermission('agent:action:propose')")
    public R<PlanResourceLocalReplanDTO.Scenario> assessExecutionDeviation(
            @RequestBody PlanResourceLocalReplanDTO.ExecutionDeviationAssessCommand command) {
        return R.ok(service.assessExecutionDeviation(command));
    }

    @PostMapping("/recovery-optimize/assess")
    @SysLog("设备恢复后评估已发布排程再优化候选")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_recoveryOptimize')")
    public R<PlanResourceLocalReplanDTO.Scenario> assessRecovery(
            @RequestBody PlanResourceLocalReplanDTO.RecoveryOptimizeCommand command) {
        return R.ok(service.assessRecovery(command));
    }

    @PostMapping("/select")
    @SysLog("选择资源异常局部重排候选")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_assess')")
    public R<PlanResourceLocalReplanDTO.Scenario> select(
            @RequestBody PlanResourceLocalReplanDTO.SelectCommand command) {
        return R.ok(service.select(command));
    }

    @PostMapping("/publish")
    @SysLog("复核发布资源异常局部重排")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_publish')")
    public R<PlanResourceLocalReplanDTO.PublishReceipt> publish(
            @RequestBody PlanResourceLocalReplanDTO.PublishCommand command) {
        return R.ok(service.publish(command));
    }

    /** 原号目录、应用及补偿均为只读查询，不在查询请求中自动补记或重新派工。 */
    @GetMapping("/case/{caseId}/mps-publication-recovery")
    @PreAuthorize("@pms.hasPermission('plan_mps_view') and @pms.hasPermission('plan_resourceReplan_view')")
    public R<PlnMpsPublicationRecoveryDTO> mpsPublicationRecovery(@PathVariable("caseId") Long caseId) {
        return R.ok(service.mpsPublicationRecovery(caseId));
    }

    @PostMapping("/cancel")
    @SysLog("取消资源异常局部重排候选")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_assess')")
    public R<Boolean> cancel(@RequestBody PlanResourceLocalReplanDTO.CancelCommand command) {
        return R.ok(service.cancel(command));
    }

    @PostMapping("/rollback")
    @SysLog("受控回退资源异常局部重排")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_rollback')")
    public R<PlanResourceLocalReplanDTO.PublishReceipt> rollback(
            @RequestBody PlanResourceLocalReplanDTO.RollbackCommand command) {
        return R.ok(service.rollback(command));
    }

    @GetMapping("/case/{caseId}/recovery-review")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_view')")
    public R<PlanResourceLocalReplanDTO.RecoveryReviewPreview> recoveryReviewPreview(@PathVariable Long caseId) {
        return R.ok(service.recoveryReviewPreview(caseId));
    }

    @GetMapping("/case/{caseId}/resume-observations")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_view')")
    public R<List<PlnRepairResumeObservation>> resumeObservations(@PathVariable Long caseId) {
        return R.ok(service.resumeObservations(caseId));
    }

    @PostMapping("/recovery-review/decide")
    @SysLog("受控办理设备恢复后的已发布排程复核")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_recoveryReview')")
    public R<PlnResourceReplanOperationEntity> decideRecoveryReview(
            @RequestBody PlanResourceLocalReplanDTO.RecoveryReviewCommand command) {
        return R.ok(service.decideRecoveryReview(command));
    }
}
