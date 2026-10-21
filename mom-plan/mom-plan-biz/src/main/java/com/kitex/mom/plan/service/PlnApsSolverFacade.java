package com.kitex.mom.plan.service;

import com.kitex.mom.plan.dto.PlanSchedulingAgentDTO;
import com.kitex.mom.plan.dto.PlnApsDecisionDTO;

/**
 * 智能 APS 案件到现有确定性排产引擎的唯一适配入口。
 *
 * <p>该门面只做案件、策略、正式计划合同和资源证据门禁，实际求解继续复用
 * {@link PlnSchedulingAgentService}，不复制排序、有限产能和硬约束算法。</p>
 */
public interface PlnApsSolverFacade {

    /** 复核案件证据并预览现有 APS 求解范围，不产生情景。 */
    PlnApsDecisionDTO.Preview preview(Long caseId, PlnApsDecisionDTO.RunCommand command);

    /** 复核证据后运行多候选求解，并把唯一情景原子绑定到案件。 */
    PlanSchedulingAgentDTO.ScenarioEvidence run(Long caseId, PlnApsDecisionDTO.RunCommand command);
}
