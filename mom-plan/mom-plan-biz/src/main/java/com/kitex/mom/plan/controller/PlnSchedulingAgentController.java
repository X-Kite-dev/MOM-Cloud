package com.kitex.mom.plan.controller;

import com.kitex.common.core.util.R;
import com.kitex.mom.plan.agent.dto.PlanAgentObjectCandidateDTO;
import com.kitex.mom.plan.agent.dto.PlanProductionScheduleCommitmentDTO;
import com.kitex.mom.plan.agent.dto.PlanResourceReplanAdvisoryDTO;
import com.kitex.mom.plan.agent.service.PlanAgentObjectCandidateService;
import com.kitex.mom.plan.agent.service.PlanResourceReplanAdvisoryQueryService;
import com.kitex.mom.plan.dto.PlanSchedulingAgentDTO;
import com.kitex.mom.plan.dto.PlnSchedulingScenarioRunDTO;
import com.kitex.mom.plan.service.PlnSchedulingAgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/plnSchedulingAgent")
public class PlnSchedulingAgentController {
    private final PlnSchedulingAgentService service;
    private final PlanResourceReplanAdvisoryQueryService resourceReplanAdvisoryQueryService;
    private final PlanAgentObjectCandidateService objectCandidateService;

    /**
     * 当前实名工厂的真实APS情景候选，不把候选排程任务当情景，也不运行或发布。
     */
    @PostMapping("/scenario/candidates")
    @PreAuthorize("@pms.hasPermission('agent:run:view')")
    public R<PlanAgentObjectCandidateDTO.CandidatePage> scenarioCandidates(
            @Valid @RequestBody PlanAgentObjectCandidateDTO.Query query) {
        return R.ok(objectCandidateService.candidates(query));
    }

    @PostMapping("/scenario/preview")
    @PreAuthorize("@pms.hasPermission('agent:scheduling:run')")
    public R<PlanSchedulingAgentDTO.Readiness> preview(@RequestBody PlnSchedulingScenarioRunDTO command) {
        return R.ok(service.preview(command));
    }

    @PostMapping("/scenario/run")
    @PreAuthorize("@pms.hasPermission('agent:scheduling:run')")
    public R<PlanSchedulingAgentDTO.ScenarioEvidence> run(@RequestBody PlnSchedulingScenarioRunDTO command) {
        return R.ok(service.run(command));
    }

    @PostMapping("/scenario/query")
    @PreAuthorize("@pms.hasPermission('agent:run:view')")
    public R<PlanSchedulingAgentDTO.ScenarioEvidence> query(@RequestBody PlanSchedulingAgentDTO.ScenarioQuery query) {
        return R.ok(service.query(query));
    }

    /**
     * 计划员工作台、Agent解释和效果复盘共同消费的同口径只读决策证据。
     */
    @PostMapping("/scenario/decision-context")
    @PreAuthorize("@pms.hasPermission('agent:run:view')")
    public R<PlanSchedulingAgentDTO.DecisionContext> decisionContext(
            @RequestBody PlanSchedulingAgentDTO.ScenarioQuery query) {
        return R.ok(service.decisionContext(query));
    }

    @PostMapping("/scenario/effect")
    @PreAuthorize("@pms.hasPermission('agent:run:view')")
    public R<PlanSchedulingAgentDTO.EffectEvidence> effect(
            @RequestBody PlanSchedulingAgentDTO.ScenarioQuery query) {
        return R.ok(service.effect(query));
    }

    @PostMapping("/production/commitment")
    @PreAuthorize("@pms.hasPermission('agent:run:view')")
    public R<PlanProductionScheduleCommitmentDTO.Evidence> productionCommitment(
            @RequestBody PlanProductionScheduleCommitmentDTO.Query query) {
        return R.ok(service.productionCommitment(query));
    }

    @PostMapping("/production/resource-replan-advisory")
    @PreAuthorize("@pms.hasPermission('plan_resourceReplan_view')")
    public R<PlanResourceReplanAdvisoryDTO.Evidence> resourceReplanAdvisory(
            @Valid @RequestBody PlanResourceReplanAdvisoryDTO.Query query) {
        return R.ok(resourceReplanAdvisoryQueryService.query(query));
    }

    @PostMapping("/objective-template/catalog")
    @PreAuthorize("@pms.hasPermission('agent:run:view')")
    public R<PlanSchedulingAgentDTO.ObjectiveTemplateCatalog> objectiveTemplates(
            @RequestBody PlanSchedulingAgentDTO.ObjectiveTemplateQuery query) {
        return R.ok(service.objectiveTemplates(query));
    }

    @PostMapping("/scenario/select")
    @PreAuthorize("@pms.hasPermission('agent:approval:execute')")
    public R<PlanSchedulingAgentDTO.ScenarioEvidence> select(@RequestBody PlanSchedulingAgentDTO.SelectCommand command) {
        return R.ok(service.select(command));
    }

    @PostMapping("/scenario/publish")
    @PreAuthorize("@pms.hasPermission('agent:approval:execute')")
    public R<PlanSchedulingAgentDTO.PublishReceipt> publish(@RequestBody PlanSchedulingAgentDTO.PublishCommand command) {
        return R.ok(service.publish(command));
    }
}
