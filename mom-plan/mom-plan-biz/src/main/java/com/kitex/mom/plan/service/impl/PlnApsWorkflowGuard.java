package com.kitex.mom.plan.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.constant.PlnApsCaseStatusEnum;
import com.kitex.mom.plan.constant.PlnApsStageEnum;
import com.kitex.mom.plan.entity.PlnApsCaseEntity;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 智能 APS 案件状态机。
 *
 * <p>调用方提交动作而非目标状态，避免浏览器或 Agent 任意跳过资源取证、A2、A3 和 MES 下达阶段。</p>
 */
@Component
public class PlnApsWorkflowGuard {

    private static final Map<String, Transition> TRANSITIONS = Map.ofEntries(
            entry("START_PREPARATION", Set.of("DRAFT", "FAILED"), "PREPARING", "PREPARATION", "RESOURCE_COLLECTION", "plan_apsResource_capture"),
            entry("MARK_BLOCKED", Set.of("PREPARING", "READY"), "BLOCKED", "PREPARATION", "READINESS_REVIEW", "plan_apsResource_capture"),
            entry("MARK_READY", Set.of("PREPARING", "BLOCKED"), "READY", "STRATEGY", "OBJECTIVE_SETUP", "plan_apsResource_capture"),
            entry("CONFIRM_STRATEGY", Set.of("READY"), "READY", "CALCULATION", "READY_TO_RUN", "plan_apsScenario_run"),
            entry("START_CALCULATION", Set.of("READY"), "CALCULATING", "CALCULATION", "SOLVING", "plan_apsScenario_run"),
            entry("COMPLETE_CALCULATION", Set.of("CALCULATING"), "EVALUATED", "DECISION", "CANDIDATE_COMPARISON", "plan_apsScenario_run"),
            entry("SELECT_CANDIDATE", Set.of("EVALUATED"), "SELECTED", "DECISION", "A2_SELECTED", "plan_apsScenario_select"),
            entry("START_PUBLISH", Set.of("SELECTED"), "PUBLISHING", "DECISION", "A3_REVIEW", "plan_apsScenario_publish"),
            entry("PUBLISH", Set.of("PUBLISHING"), "PUBLISHED", "EXECUTION", "WAITING_DISPATCH", "plan_apsScenario_publish"),
            entry("START_DISPATCH", Set.of("PUBLISHED"), "DISPATCHING", "EXECUTION", "MES_DISPATCH", "plan_apsDispatch_retry"),
            entry("START_EXECUTION", Set.of("PUBLISHED", "DISPATCHING"), "EXECUTING", "EXECUTION", "EXECUTION_TRACKING", "plan_apsEffect_review"),
            entry("COMPLETE", Set.of("EXECUTING"), "COMPLETED", "EXECUTION", "EFFECT_CONFIRMED", "plan_apsEffect_review"),
            entry("FAIL", Set.of("PREPARING", "READY", "CALCULATING", "EVALUATED", "SELECTED", "PUBLISHING", "DISPATCHING", "EXECUTING"), "FAILED", null, "FAILED", "plan_apsCorrection_major"),
            entry("CANCEL", Set.of("DRAFT", "PREPARING", "BLOCKED", "READY", "CALCULATING", "EVALUATED", "SELECTED", "FAILED"), "CANCELLED", null, "CANCELLED", "plan_apsCase_create"),
            entry("SUPERSEDE", Set.of("DRAFT", "PREPARING", "BLOCKED", "READY", "CALCULATING", "EVALUATED", "SELECTED", "PUBLISHING", "PUBLISHED", "DISPATCHING", "EXECUTING", "FAILED"), "SUPERSEDED", null, "SUPERSEDED", "plan_apsCorrection_major")
    );

    /** 校验动作并给出唯一合法迁移。 */
    public Transition requireTransition(PlnApsCaseEntity current, String action) {
        if (current == null || action == null) {
            throw new CheckedException("APS案件状态迁移参数不完整");
        }
        Transition transition = TRANSITIONS.get(action.trim().toUpperCase());
        if (transition == null) {
            throw new CheckedException("不支持的APS案件动作：" + action);
        }
        if (!PlnApsCaseStatusEnum.isValid(current.getCaseStatus())
                || !PlnApsStageEnum.isValid(current.getCurrentStage())) {
            throw new CheckedException("APS案件当前状态或阶段不在受控值域内");
        }
        if (!transition.allowedStatuses().contains(current.getCaseStatus())) {
            throw new CheckedException("APS案件当前状态不允许执行动作：" + action);
        }
        return transition.stage() == null
                ? transition.withStage(current.getCurrentStage()) : transition;
    }

    private static Map.Entry<String, Transition> entry(String action, Set<String> allowed, String status,
                                                        String stage, String step, String permission) {
        return Map.entry(action, new Transition(allowed, status, stage, step, permission));
    }

    /** 状态机校验后的不可变迁移结果。 */
    public record Transition(Set<String> allowedStatuses, String status, String stage,
                             String step, String requiredPermission) {
        private Transition withStage(String actualStage) {
            return new Transition(allowedStatuses, status, actualStage, step, requiredPermission);
        }
    }
}
