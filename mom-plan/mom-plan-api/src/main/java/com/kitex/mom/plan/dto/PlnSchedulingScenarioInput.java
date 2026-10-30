package com.kitex.mom.plan.dto;

import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnSchedulingRuleSetEntity;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 情景来源与规则快照，不代表跨服务资源的一致性快照。
 */
@Data
public class PlnSchedulingScenarioInput {
    private String version = "APS_SCENARIO_INPUT_V1";
    private LocalDate capturedDate;
    private PlnSchedulingScenarioRunDTO run;
    private List<PlnIssueEntity> sources;
    private PlnSchedulingRuleSetEntity rule;
    private Map<String, String> solverSpecs;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String objectiveTemplateSnapshot;
}
