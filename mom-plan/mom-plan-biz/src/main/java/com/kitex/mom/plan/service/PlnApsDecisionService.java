package com.kitex.mom.plan.service;

import com.kitex.mom.plan.dto.PlanSchedulingAgentDTO;
import com.kitex.mom.plan.dto.PlnApsDecisionDTO;
import com.kitex.mom.plan.entity.PlnSchedulingOperationEntity;

import java.util.List;

/** 智能 APS 案件的候选比较、A2 人工选案和 A3 正式发布服务。 */
public interface PlnApsDecisionService {

    /** 查询案件绑定情景的同口径候选比较证据。 */
    PlnApsDecisionDTO.Comparison compare(Long caseId);

    /** 只读当前案件候选的工序及冻结可选资源，供专业调整预览。 */
    List<PlnSchedulingOperationEntity> candidateOperations(Long caseId, Long taskId);

    /** 从现有候选受控派生新版本；原候选及正式计划均保持不变。 */
    PlnApsDecisionDTO.Comparison derive(Long caseId, PlnApsDecisionDTO.DeriveCommand command);

    /** 人工选择可行候选并固化 A2 证据。 */
    PlanSchedulingAgentDTO.ScenarioEvidence select(Long caseId, PlnApsDecisionDTO.SelectCommand command);

    /** 异岗审批后重新取证并正式发布 A2 已选候选。 */
    PlnApsDecisionDTO.PublishResult publish(Long caseId, PlnApsDecisionDTO.PublishCommand command);
}
