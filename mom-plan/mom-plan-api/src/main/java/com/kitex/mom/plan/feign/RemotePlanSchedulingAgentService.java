package com.kitex.mom.plan.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.Constants;
import com.kitex.mom.plan.agent.dto.PlanProductionScheduleCommitmentDTO;
import com.kitex.mom.plan.agent.dto.PlanResourceReplanAdvisoryDTO;
import com.kitex.mom.plan.dto.PlanSchedulingAgentDTO;
import com.kitex.mom.plan.dto.PlnSchedulingScenarioRunDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(contextId = "remotePlanSchedulingAgentService", value = Constants.Name)
public interface RemotePlanSchedulingAgentService {
    @PostMapping("/plnSchedulingAgent/scenario/preview")
    R<PlanSchedulingAgentDTO.Readiness> preview(@RequestBody PlnSchedulingScenarioRunDTO command);

    @PostMapping("/plnSchedulingAgent/scenario/run")
    R<PlanSchedulingAgentDTO.ScenarioEvidence> run(@RequestBody PlnSchedulingScenarioRunDTO command);

    @PostMapping("/plnSchedulingAgent/scenario/query")
    R<PlanSchedulingAgentDTO.ScenarioEvidence> query(@RequestBody PlanSchedulingAgentDTO.ScenarioQuery query);

    @PostMapping("/plnSchedulingAgent/scenario/effect")
    R<PlanSchedulingAgentDTO.EffectEvidence> effect(@RequestBody PlanSchedulingAgentDTO.ScenarioQuery query);

    @PostMapping("/plnSchedulingAgent/production/commitment")
    R<PlanProductionScheduleCommitmentDTO.Evidence> productionCommitment(
            @RequestBody PlanProductionScheduleCommitmentDTO.Query query);

    @PostMapping("/plnSchedulingAgent/production/resource-replan-advisory")
    R<PlanResourceReplanAdvisoryDTO.Evidence> resourceReplanAdvisory(
            @RequestBody PlanResourceReplanAdvisoryDTO.Query query);

    @PostMapping("/plnSchedulingAgent/objective-template/catalog")
    R<PlanSchedulingAgentDTO.ObjectiveTemplateCatalog> objectiveTemplates(
            @RequestBody PlanSchedulingAgentDTO.ObjectiveTemplateQuery query);

    @PostMapping("/plnSchedulingAgent/scenario/select")
    R<PlanSchedulingAgentDTO.ScenarioEvidence> select(@RequestBody PlanSchedulingAgentDTO.SelectCommand command);

    @PostMapping("/plnSchedulingAgent/scenario/publish")
    R<PlanSchedulingAgentDTO.PublishReceipt> publish(@RequestBody PlanSchedulingAgentDTO.PublishCommand command);
}
