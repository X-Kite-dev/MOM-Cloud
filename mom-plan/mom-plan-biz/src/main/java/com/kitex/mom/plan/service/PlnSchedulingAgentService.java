package com.kitex.mom.plan.service;

import com.kitex.mom.plan.agent.dto.PlanProductionScheduleCommitmentDTO;
import com.kitex.mom.plan.dto.PlanSchedulingAgentDTO;
import com.kitex.mom.plan.dto.PlnSchedulingScenarioRunDTO;

/**
 * 智能排产 Agent 的受控业务入口。
 *
 * <p>Agent 负责组织情景证据、生成候选和解释建议，但不能绕过 APS 的硬约束校验、人工选案和发布审批。
 * 该接口明确区分“仿真建议”和“正式发布”，避免模型输出直接修改生产计划。</p>
 */
public interface PlnSchedulingAgentService {

    /**
     * 预检待排计划、规则和候选配置，不创建排产情景，也不修改正式计划。
     */
    PlanSchedulingAgentDTO.Readiness preview(PlnSchedulingScenarioRunDTO command);

    /**
     * 运行排产情景并生成可审计的候选证据包。
     *
     * @param command 情景范围、目标权重、约束和幂等信息
     * @return 候选方案、评分、约束结论和解释证据
     */
    PlanSchedulingAgentDTO.ScenarioEvidence run(PlnSchedulingScenarioRunDTO command);

    /**
     * 查询既有排产情景的完整证据和候选状态。
     */
    PlanSchedulingAgentDTO.ScenarioEvidence query(PlanSchedulingAgentDTO.ScenarioQuery query);

    /**
     * 重采并冻结计划员、Agent解释和效果复盘共同使用的决策上下文。
     */
    PlanSchedulingAgentDTO.DecisionContext decisionContext(PlanSchedulingAgentDTO.ScenarioQuery query);

    /**
     * 查询已发布排产情景在MES中的实际执行效果；不触发排产或正式计划变更。
     */
    PlanSchedulingAgentDTO.EffectEvidence effect(PlanSchedulingAgentDTO.ScenarioQuery query);

    /**
     * 按MES工单的权威来源映射查询当前锁定的PLAN工序排程承诺。
     */
    PlanProductionScheduleCommitmentDTO.Evidence productionCommitment(
            PlanProductionScheduleCommitmentDTO.Query query);

    /**
     * 查询当前工厂已批准且仍可用于新试算的行业目标模板版本。
     */
    PlanSchedulingAgentDTO.ObjectiveTemplateCatalog objectiveTemplates(
            PlanSchedulingAgentDTO.ObjectiveTemplateQuery query);

    /**
     * 人工选择唯一可发布候选，并固化选择原因和来源指纹。
     */
    PlanSchedulingAgentDTO.ScenarioEvidence select(PlanSchedulingAgentDTO.SelectCommand command);

    /**
     * 在重新校验正式计划与执行事实后发布已选候选。
     */
    PlanSchedulingAgentDTO.PublishReceipt publish(PlanSchedulingAgentDTO.PublishCommand command);
}
