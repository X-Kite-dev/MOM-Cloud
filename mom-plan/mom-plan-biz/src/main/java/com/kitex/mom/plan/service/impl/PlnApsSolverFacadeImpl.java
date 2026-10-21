package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.PlanSchedulingAgentDTO;
import com.kitex.mom.plan.dto.PlnApsCaseCommand;
import com.kitex.mom.plan.dto.PlnApsCaseDTO;
import com.kitex.mom.plan.dto.PlnApsDecisionDTO;
import com.kitex.mom.plan.dto.PlnApsResourceDTO;
import com.kitex.mom.plan.dto.PlnSchedulingScenarioRunDTO;
import com.kitex.mom.plan.service.PlnApsCaseService;
import com.kitex.mom.plan.service.PlnApsReadinessService;
import com.kitex.mom.plan.service.PlnApsResourceSnapshotService;
import com.kitex.mom.plan.service.PlnApsSolverFacade;
import com.kitex.mom.plan.service.PlnSchedulingAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 案件级 APS 求解门面。
 *
 * <p>正式运行前重新核验工厂计划权威、正式计划合同及跨模块资源快照；通过后只调用既有
 * {@link PlnSchedulingAgentService}，并将返回情景原子绑定到案件。</p>
 */
@Service
@RequiredArgsConstructor
public class PlnApsSolverFacadeImpl implements PlnApsSolverFacade {

    private static final Set<String> MRP_AUTHORITIES = Set.of("ERP_MRP", "MOM_MRP");

    private final PlnApsCaseService caseService;
    private final PlnApsResourceSnapshotService resourceSnapshotService;
    private final PlnApsReadinessService readinessService;
    private final PlnSchedulingAgentService schedulingAgentService;

    @Override
    public PlnApsDecisionDTO.Preview preview(Long caseId, PlnApsDecisionDTO.RunCommand command) {
        require(command != null, "APS求解预检参数不能为空");
        PlnApsCaseDTO apsCase = caseService.detail(caseId);
        List<String> blockers = gateBlockers(apsCase, command.getExpectedCaseFingerprint());
        PlnApsResourceDTO.Snapshot snapshot = null;
        PlanSchedulingAgentDTO.Readiness solver = null;
        if (blockers.isEmpty()) {
            snapshot = currentSnapshot(apsCase, true);
            if (snapshot.getStale() || !Boolean.TRUE.equals(snapshot.getComplete())
                    || !"READY".equals(snapshot.getReadinessStatus())) {
                blockers.add(StrUtil.blankToDefault(snapshot.getStaleReason(),
                        "跨模块资源证据尚未达到可求解状态：" + snapshot.getReadinessStatus()));
            } else {
                solver = schedulingAgentService.preview(toScenarioCommand(apsCase, command));
                if (!Boolean.TRUE.equals(solver.getReady())) blockers.add(solver.getSummary());
            }
        }
        return new PlnApsDecisionDTO.Preview().setApsCase(apsCase).setResourceSnapshot(snapshot)
                .setSolverReadiness(solver).setReady(blockers.isEmpty()).setBlockers(List.copyOf(blockers));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanSchedulingAgentDTO.ScenarioEvidence run(Long caseId, PlnApsDecisionDTO.RunCommand command) {
        require(command != null, "APS求解参数不能为空");
        require(StrUtil.isNotBlank(command.getRequestId()) && command.getRequestId().trim().length() <= 64,
                "APS求解请求号不能为空且不能超过64个字符");
        PlnApsCaseDTO current = caseService.detail(caseId);
        PlnSchedulingScenarioRunDTO scenarioCommand = toScenarioCommand(current, command);
        if ("EVALUATED".equals(current.getCaseStatus()) && StrUtil.isNotBlank(current.getScenarioId())) {
            PlanSchedulingAgentDTO.ScenarioEvidence replay = schedulingAgentService.run(scenarioCommand);
            require(Objects.equals(parseId(current.getScenarioId(), "案件情景主键"),
                            replay.getScenario().getScenario().getId()),
                    "同一求解请求已产生其他APS情景，拒绝覆盖案件结果");
            return replay;
        }

        List<String> blockers = gateBlockers(current, command.getExpectedCaseFingerprint());
        require(blockers.isEmpty(), String.join("；", blockers));
        PlnApsResourceDTO.Snapshot snapshot = currentSnapshot(current, true);
        require(!Boolean.TRUE.equals(snapshot.getStale()) && Boolean.TRUE.equals(snapshot.getComplete())
                        && "READY".equals(snapshot.getReadinessStatus()),
                StrUtil.blankToDefault(snapshot.getStaleReason(), "跨模块资源证据尚未达到可求解状态"));
        PlanSchedulingAgentDTO.Readiness readiness = schedulingAgentService.preview(scenarioCommand);
        require(Boolean.TRUE.equals(readiness.getReady()), readiness.getSummary());

        if ("STRATEGY".equals(current.getCurrentStage())) {
            current = advance(caseId, current, "CONFIRM_STRATEGY", suffix(command.getRequestId(), "STRATEGY"),
                    "冻结本次APS候选目标、范围和资源证据");
        }
        current = advance(caseId, current, "START_CALCULATION", suffix(command.getRequestId(), "START"),
                "开始调用现有确定性APS引擎生成多候选方案");
        PlanSchedulingAgentDTO.ScenarioEvidence evidence = schedulingAgentService.run(scenarioCommand);
        require(evidence != null && evidence.getScenario() != null && evidence.getScenario().getScenario() != null,
                "APS求解未返回可绑定情景");
        Long scenarioId = evidence.getScenario().getScenario().getId();
        caseService.bindScenarioResult(caseId, scenarioId, suffix(command.getRequestId(), "RESULT"),
                current.getContextFingerprint(), "完成多候选求解并绑定情景" + evidence.getScenario().getScenario().getScenarioNo());
        return evidence;
    }

    private PlnApsCaseDTO advance(Long caseId, PlnApsCaseDTO current, String action,
                                  String requestId, String reason) {
        PlnApsCaseCommand.Advance command = new PlnApsCaseCommand.Advance();
        command.setAction(action);
        command.setRequestId(requestId);
        command.setExpectedVersion(current.getWorkflowVersion());
        command.setExpectedContextFingerprint(current.getContextFingerprint());
        command.setReason(reason);
        return caseService.advance(caseId, command);
    }

    private PlnApsResourceDTO.Snapshot currentSnapshot(PlnApsCaseDTO apsCase, boolean recheck) {
        Long caseId = parseId(apsCase.getId(), "APS案件主键");
        Long snapshotId = parseId(apsCase.getResourceSnapshotId(), "APS资源快照主键");
        PlnApsResourceDTO.Snapshot snapshot = recheck
                ? readinessService.recheck(caseId, snapshotId)
                : resourceSnapshotService.detail(caseId, snapshotId);
        require(Objects.equals(snapshot.getSnapshotId(), apsCase.getResourceSnapshotId())
                        && Objects.equals(snapshot.getResourceFingerprint(), apsCase.getResourceFingerprint()),
                "案件引用的资源快照或指纹已变化，请重新采集");
        return snapshot;
    }

    private List<String> gateBlockers(PlnApsCaseDTO apsCase, String expectedCaseFingerprint) {
        List<String> blockers = new ArrayList<>();
        if (!"READY".equals(apsCase.getCaseStatus())) blockers.add("只有READY案件可以进入APS求解");
        if (!isFingerprint(expectedCaseFingerprint)
                || !Objects.equals(apsCase.getContextFingerprint(), expectedCaseFingerprint)) {
            blockers.add("APS案件上下文已变化，请刷新工作台");
        }
        if (StrUtil.isBlank(apsCase.getPlanningPolicyId()) || apsCase.getPlanningPolicyVersion() == null
                || !isFingerprint(apsCase.getPlanningPolicyFingerprint())) {
            blockers.add("工厂计划权威策略尚未形成有效版本");
        }
        if (!MRP_AUTHORITIES.contains(apsCase.getMrpAuthority())) {
            blockers.add("工厂MRP权威模式未明确为ERP_MRP或MOM_MRP");
        }
        if (StrUtil.isBlank(apsCase.getFormalPlanContractId())
                || StrUtil.isBlank(apsCase.getFormalPlanContractNo())
                || apsCase.getFormalPlanContractVersion() == null
                || !isFingerprint(apsCase.getFormalPlanContractFingerprint())) {
            blockers.add("正式计划合同尚未形成有效版本");
        }
        if (StrUtil.isBlank(apsCase.getResourceSnapshotId()) || !isFingerprint(apsCase.getResourceFingerprint())) {
            blockers.add("跨模块资源快照尚未采集或指纹无效");
        }
        return blockers;
    }

    private PlnSchedulingScenarioRunDTO toScenarioCommand(PlnApsCaseDTO apsCase,
                                                           PlnApsDecisionDTO.RunCommand source) {
        PlnSchedulingScenarioRunDTO target = new PlnSchedulingScenarioRunDTO();
        target.setRequestId(trim(source.getRequestId()));
        target.setApsCaseId(parseId(apsCase.getId(), "APS案件主键"));
        target.setResourceSnapshotId(parseId(apsCase.getResourceSnapshotId(), "APS资源快照主键"));
        target.setExpectedResourceFingerprint(apsCase.getResourceFingerprint());
        target.setFactoryCode(apsCase.getFactoryCode());
        target.setTriggerType("APS_CASE");
        target.setScheduleDate(source.getScheduleDate());
        target.setCycleType(source.getCycleType());
        target.setPlanNum(source.getPlanNum());
        target.setTargetIds(parseIds(source.getTargetIds(), "生产计划主键"));
        target.setCandidateProfiles(source.getCandidateProfiles());
        target.setObjectiveTemplateId(parseOptionalId(source.getObjectiveTemplateId(), "目标模板主键"));
        target.setExpectedObjectiveTemplateFingerprint(source.getExpectedObjectiveTemplateFingerprint());
        return target;
    }

    private static List<Long> parseIds(List<String> values, String label) {
        if (values == null) return null;
        return values.stream().map(value -> parseId(value, label)).distinct().toList();
    }

    private static Long parseOptionalId(String value, String label) {
        return StrUtil.isBlank(value) ? null : parseId(value, label);
    }

    private static Long parseId(String value, String label) {
        try {
            Long id = Long.valueOf(value);
            require(id > 0, label + "必须为正整数");
            return id;
        } catch (RuntimeException ex) {
            if (ex instanceof CheckedException checked) throw checked;
            throw new CheckedException(label + "格式不正确");
        }
    }

    private static String suffix(String requestId, String phase) {
        return requestId.trim() + '#' + phase;
    }

    private static boolean isFingerprint(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
