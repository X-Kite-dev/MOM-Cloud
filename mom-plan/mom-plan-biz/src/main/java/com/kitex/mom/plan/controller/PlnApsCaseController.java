package com.kitex.mom.plan.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.util.R;
import com.kitex.common.log.annotation.SysLog;
import com.kitex.mom.plan.dto.*;
import com.kitex.mom.plan.entity.PlnSchedulingOperationEntity;
import com.kitex.mom.plan.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 智能 APS 案件中心接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/plnApsCase")
@Tag(name = "智能APS案件中心")
@SecurityRequirement(name = HttpHeaders.AUTHORIZATION)
public class PlnApsCaseController {

    private final PlnApsCaseService service;
    private final PlnApsResourceSnapshotService resourceSnapshotService;
    private final PlnApsReadinessService readinessService;
    private final PlnApsSolverFacade solverFacade;
    private final PlnApsLocalReplanWorkbenchService localReplanWorkbenchService;
    private final PlnApsLocalExecutionService localExecutionService;
    private final PlnApsDecisionService decisionService;
    private final PlnApsA3ApprovalService approvalService;
    private final PlnApsReplanAdapter replanAdapter;
    private final PlnApsEffectService effectService;

    @PostMapping("/launch")
    @Operation(summary = "从业务来源创建或幂等复用排产案件")
    @SysLog("创建智能APS排产案件")
    @PreAuthorize("@pms.hasPermission('plan_apsCase_create')")
    public R<PlnApsCaseDTO> launch(@RequestBody PlnApsCaseCommand.Launch command) {
        return R.ok(service.launch(command));
    }

    @PostMapping("/replan/launch")
    @Operation(summary = "从既有缺料、资源、质量、插单或执行偏差事实启动统一案件")
    @SysLog("从专业重排事实创建智能APS案件")
    @PreAuthorize("@pms.hasPermission('plan_apsCase_create')")
    public R<PlnApsCaseDTO> launchReplan(@RequestBody PlnApsReplanDTO.LaunchCommand command) {
        return R.ok(replanAdapter.launchFromReplanSource(command));
    }

    @PostMapping("/effect-review/{reviewId}/launch")
    @Operation(summary = "判定排程效果偏差并按需创建待确认案件")
    @SysLog("从排程效果回评创建智能APS案件")
    @PreAuthorize("@pms.hasPermission('plan_apsCase_create')")
    public R<PlnApsReplanDTO.EffectCaseResult> launchEffectReview(
            @PathVariable Long reviewId,
            @RequestBody PlnApsReplanDTO.EffectLaunchCommand command) {
        return R.ok(effectService.launchFromEffectReview(reviewId, command));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询当前工厂排产案件")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
    public R<Page<PlnApsCaseDTO>> page(@ParameterObject SearchPage page,
                                       @ParameterObject PlnApsCaseCommand.Query query) {
        return R.ok(service.page(page, query));
    }

    @GetMapping("/{caseId}")
    @Operation(summary = "查询排产案件详情与最近履历")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
    public R<PlnApsCaseDTO> detail(@PathVariable Long caseId) {
        return R.ok(service.detail(caseId));
    }

    @GetMapping("/{caseId}/replan")
    @Operation(summary = "查询专业重排事实在统一案件中的只读投影")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
    public R<PlnApsReplanDTO.Projection> replanProjection(@PathVariable Long caseId) {
        return R.ok(replanAdapter.projection(caseId));
    }

    @PostMapping("/{caseId}/advance")
    @Operation(summary = "通过显式领域动作推进排产案件")
    @SysLog("推进智能APS排产案件")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
    public R<PlnApsCaseDTO> advance(@PathVariable Long caseId,
                                    @RequestBody PlnApsCaseCommand.Advance command) {
        return R.ok(service.advance(caseId, command));
    }

    @PostMapping("/{caseId}/cancel")
    @Operation(summary = "取消尚未正式发布的排产案件")
    @SysLog("取消智能APS排产案件")
    @PreAuthorize("@pms.hasPermission('plan_apsCase_create')")
    public R<PlnApsCaseDTO> cancel(@PathVariable Long caseId,
                                   @RequestBody PlnApsCaseCommand.Cancel command) {
        return R.ok(service.cancel(caseId, command));
    }

    @PostMapping("/{caseId}/resources/capture")
    @Operation(summary = "跨模块采集资源证据并生成不可变快照")
    @SysLog("采集智能APS资源证据")
    @PreAuthorize("@pms.hasPermission('plan_apsResource_capture')")
    public R<PlnApsResourceDTO.Snapshot> capture(@PathVariable Long caseId,
                                                 @RequestBody PlnApsResourceDTO.CaptureCommand command) {
        return R.ok(resourceSnapshotService.capture(caseId, command.getRequestId(),
                command.getExpectedCaseFingerprint()));
    }

    @GetMapping("/{caseId}/resources/{snapshotId}")
    @Operation(summary = "查询资源快照和结构化预检问题")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
    public R<PlnApsResourceDTO.Snapshot> resourceSnapshot(@PathVariable Long caseId,
                                                          @PathVariable Long snapshotId) {
        return R.ok(resourceSnapshotService.detail(caseId, snapshotId));
    }

    @PostMapping("/{caseId}/resources/{snapshotId}/recheck")
    @Operation(summary = "重新采集关键来源并判断快照是否失效")
    @SysLog("复核智能APS资源快照")
    @PreAuthorize("@pms.hasPermission('plan_apsResource_capture')")
    public R<PlnApsResourceDTO.Snapshot> recheck(@PathVariable Long caseId,
                                                 @PathVariable Long snapshotId) {
        return R.ok(readinessService.recheck(caseId, snapshotId));
    }

    @PostMapping("/{caseId}/solver/preview")
    @Operation(summary = "复核案件证据并预览现有APS求解范围")
    @PreAuthorize("@pms.hasPermission('plan_apsScenario_run')")
    public R<PlnApsDecisionDTO.Preview> preview(@PathVariable Long caseId,
                                                @RequestBody PlnApsDecisionDTO.RunCommand command) {
        return R.ok(solverFacade.preview(caseId, command));
    }

    @PostMapping("/{caseId}/solver/run")
    @Operation(summary = "运行现有APS多候选求解并绑定案件情景")
    @SysLog("运行智能APS多候选求解")
    @PreAuthorize("@pms.hasPermission('plan_apsScenario_run')")
    public R<PlanSchedulingAgentDTO.ScenarioEvidence> run(@PathVariable Long caseId,
                                                          @RequestBody PlnApsDecisionDTO.RunCommand command) {
        return R.ok(solverFacade.run(caseId, command));
    }

    @GetMapping("/{caseId}/solver/request")
    @Operation(summary = "只读核对当前案件原计算请求回执")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
    public R<PlnApsDecisionDTO.RequestReceipt> solverRequest(@PathVariable Long caseId,
                                                           @RequestParam String requestId) {
        return R.ok(solverFacade.lookupRequest(caseId, requestId));
    }

    @GetMapping("/{caseId}/replan/workbench")
    @Operation(summary = "读取原业务局部重排候选及完整工序差异")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
    public R<PlnApsLocalReplanDTO.Workbench> localReplanWorkbench(@PathVariable Long caseId) {
        return R.ok(localReplanWorkbenchService.detail(caseId));
    }

    @GetMapping("/{caseId}/replan/candidates/{candidateId}/execution")
    @Operation(summary = "只读核对原MES应用回执、当前计划时刻和实际执行，逐计划分页")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view') and @pms.hasPermission('mesTask:show')")
    public R<PlnApsLocalExecutionDTO.Page> localExecution(@PathVariable Long caseId,
            @PathVariable String candidateId, @RequestParam(defaultValue = "1") int current,
            @RequestParam(required = false) String pendingRequestId) {
        return R.ok(localExecutionService.page(caseId, candidateId, current, pendingRequestId));
    }

    @PostMapping("/{caseId}/replan/effect-assess")
    @Operation(summary = "按原执行偏差领域生成候选并进入对应统一案件")
    @SysLog("从APS执行效果生成局部重排候选")
    @PreAuthorize("@pms.hasPermission('agent:action:propose') and @pms.hasPermission('plan_apsCase_create')")
    public R<PlnApsCaseDTO> assessEffectReplan(@PathVariable Long caseId,
            @RequestBody PlnApsLocalReplanDTO.EffectAssessCommand command) {
        return R.ok(localReplanWorkbenchService.assessEffect(caseId, command));
    }

    @GetMapping("/{caseId}/decision/compare")
    @Operation(summary = "查询案件级多候选比较和决策证据")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
    public R<PlnApsDecisionDTO.Comparison> compare(@PathVariable Long caseId) {
        return R.ok(decisionService.compare(caseId));
    }

    @GetMapping("/{caseId}/decision/candidates/{taskId}/operations")
    @Operation(summary = "读取当前案件候选工序及许可资源快照")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
    public R<java.util.List<PlnSchedulingOperationEntity>> candidateOperations(
            @PathVariable Long caseId, @PathVariable Long taskId) {
        return R.ok(decisionService.candidateOperations(caseId, taskId));
    }

    @PostMapping("/{caseId}/decision/derive")
    @Operation(summary = "从现有候选派生专业调整版本并重新核验硬约束")
    @SysLog("智能APS专业调整派生候选")
    @PreAuthorize("@pms.hasPermission('plan_apsScenario_run')")
    public R<PlnApsDecisionDTO.Comparison> derive(
            @PathVariable Long caseId, @RequestBody PlnApsDecisionDTO.DeriveCommand command) {
        return R.ok(decisionService.derive(caseId, command));
    }

    @PostMapping("/{caseId}/decision/select")
    @Operation(summary = "执行A2人工选案并绑定可行候选")
    @SysLog("智能APS执行A2人工选案")
    @PreAuthorize("@pms.hasPermission('plan_apsScenario_select')")
    public R<PlanSchedulingAgentDTO.ScenarioEvidence> select(
            @PathVariable Long caseId, @RequestBody PlnApsDecisionDTO.SelectCommand command) {
        return R.ok(decisionService.select(caseId, command));
    }

    @PostMapping("/{caseId}/decision/publish")
    @Operation(summary = "异岗A3复核后正式发布已选候选")
    @SysLog("智能APS执行A3正式发布")
    @PreAuthorize("@pms.hasPermission('plan_apsScenario_publish')")
    public R<PlnApsDecisionDTO.PublishResult> publish(
            @PathVariable Long caseId, @RequestBody PlnApsDecisionDTO.PublishCommand command) {
        return R.ok(decisionService.publish(caseId, command));
    }

    @GetMapping("/{caseId}/decision/approval")
    @Operation(summary = "查询当前APS案件A3审批状态")
    @PreAuthorize("@pms.hasPermission('plan_plnSchedulingRule_view')")
    public R<PlnApsDecisionDTO.Approval> approval(@PathVariable Long caseId) {
        return R.ok(approvalService.current(caseId));
    }

    @PostMapping("/{caseId}/decision/approval/submit")
    @Operation(summary = "送审A2已选候选和冻结证据")
    @SysLog("智能APS提交A3异岗审批")
    @PreAuthorize("@pms.hasPermission('plan_apsScenario_select')")
    public R<PlnApsDecisionDTO.Approval> submitApproval(
            @PathVariable Long caseId, @RequestBody PlnApsDecisionDTO.ApprovalSubmitCommand command) {
        return R.ok(approvalService.submit(caseId, command));
    }

    @PostMapping("/{caseId}/decision/approval/decide")
    @Operation(summary = "异岗审核或驳回APS A3审批")
    @SysLog("智能APS异岗审核A3")
    @PreAuthorize("@pms.hasPermission('plan_apsScenario_publish')")
    public R<PlnApsDecisionDTO.Approval> decideApproval(
            @PathVariable Long caseId, @RequestBody PlnApsDecisionDTO.ApprovalDecisionCommand command) {
        return R.ok(approvalService.decide(caseId, command));
    }
}
