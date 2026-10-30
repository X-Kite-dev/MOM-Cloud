package com.kitex.mom.plan.service.impl;

import com.kitex.mom.plan.service.aps.PlnApsSourceRouting;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.dto.*;
import com.kitex.mom.plan.entity.PlnSchedulingScenarioEntity;
import com.kitex.mom.plan.mapper.PlnSchedulingScenarioMapper;
import com.kitex.mom.plan.service.*;
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
    private final PlnSchedulingScenarioMapper scenarioMapper;

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

    @Override
    public PlnApsDecisionDTO.Preview preview(Long caseId, PlnApsDecisionDTO.RunCommand command) {
        require(command != null, "APS求解预检参数不能为空");
        PlnApsCaseDTO apsCase = caseService.detail(caseId);
        PlnApsSourceRouting.requireStandard(apsCase);
        List<String> blockers = gateBlockers(apsCase, command.getExpectedCaseFingerprint());
        PlnApsResourceDTO.Snapshot snapshot = null;
        PlanSchedulingAgentDTO.Readiness solver = null;
        PlnApsDecisionDTO.ScopePreview scope = null;
        if (blockers.isEmpty()) {
            snapshot = currentSnapshot(apsCase, true);
            if (snapshot.getStale() || !Boolean.TRUE.equals(snapshot.getComplete())
                    || !"READY".equals(snapshot.getReadinessStatus())) {
                blockers.add(StrUtil.blankToDefault(snapshot.getStaleReason(),
                        "跨模块资源证据尚未达到可求解状态：" + snapshot.getReadinessStatus()));
            } else {
                scope = schedulingAgentService.previewScope(toScenarioCommand(apsCase, command));
                solver = scope.getSolverReadiness();
                if (!Boolean.TRUE.equals(solver.getReady())) blockers.add(solver.getSummary());
            }
        }
        return new PlnApsDecisionDTO.Preview().setApsCase(apsCase).setResourceSnapshot(snapshot)
                .setSolverReadiness(solver).setReady(blockers.isEmpty()).setBlockers(List.copyOf(blockers))
                .setSourcePlans(scope == null ? List.of() : scope.getSourcePlans())
                .setInputFingerprint(scope == null ? null : scope.getInputFingerprint());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanSchedulingAgentDTO.ScenarioEvidence run(Long caseId, PlnApsDecisionDTO.RunCommand command) {
        require(command != null, "APS求解参数不能为空");
        require(StrUtil.isNotBlank(command.getRequestId()) && command.getRequestId().trim().length() <= 64,
                "APS求解请求号不能为空且不能超过64个字符");
        PlnApsCaseDTO current = caseService.detail(caseId);
        PlnApsSourceRouting.requireStandard(current);
        PlnSchedulingScenarioRunDTO scenarioCommand = toScenarioCommand(current, command);
        PlnSchedulingScenarioEntity existing = findRequest(current, command.getRequestId().trim());
        if (existing != null) {
            require(Objects.equals(current.getScenarioId(), existing.getId().toString()),
                    "原求解请求不属于当前案件已绑定情景");
            // 先由引擎验证原命令指纹再回放；已选案或已发布后仍可恢复原求解回执。
            PlanSchedulingAgentDTO.ScenarioEvidence replay = schedulingAgentService.run(scenarioCommand);
            require(Objects.equals(parseId(current.getScenarioId(), "案件情景主键"),
                            replay.getScenario().getScenario().getId()),
                    "同一求解请求已产生其他APS情景，拒绝覆盖案件结果");
            return replay;
        }

        require(isFingerprint(command.getExpectedInputFingerprint())
                        && command.getTargetIds() != null && !command.getTargetIds().isEmpty(),
                "请先预检并确认完整计划范围，再运行APS计算");
        List<String> blockers = gateBlockers(current, command.getExpectedCaseFingerprint());
        require(blockers.isEmpty(), String.join("；", blockers));
        PlnApsResourceDTO.Snapshot snapshot = currentSnapshot(current, true);
        require(!Boolean.TRUE.equals(snapshot.getStale()) && Boolean.TRUE.equals(snapshot.getComplete())
                        && "READY".equals(snapshot.getReadinessStatus()),
                StrUtil.blankToDefault(snapshot.getStaleReason(), "跨模块资源证据尚未达到可求解状态"));
        PlnApsDecisionDTO.ScopePreview scope = schedulingAgentService.previewScope(scenarioCommand);
        require(Boolean.TRUE.equals(scope.getSolverReadiness().getReady()), scope.getSolverReadiness().getSummary());
        require(Objects.equals(scope.getInputFingerprint(), command.getExpectedInputFingerprint()),
                "计划范围、计划内容、配置或规则已变化，请重新预检并确认");

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

    @Override
    public PlnApsDecisionDTO.RequestReceipt lookupRequest(Long caseId, String requestId) {
        require(StrUtil.isNotBlank(requestId) && requestId.trim().length() <= 64, "请提供原计算请求号");
        PlnApsCaseDTO current = caseService.detail(caseId);
        PlnApsSourceRouting.requireStandard(current);
        PlnSchedulingScenarioEntity scenario = findRequest(current, requestId.trim());
        PlnApsDecisionDTO.RequestReceipt receipt = new PlnApsDecisionDTO.RequestReceipt()
                .setCaseId(current.getId()).setRequestId(requestId.trim());
        if (scenario == null) return receipt.setState("NOT_OBSERVED")
                .setMessage("暂未读取到已提交结果，请保留原请求并继续核对或重试");
        require(Objects.equals(current.getScenarioId(), scenario.getId().toString()),
                "原求解请求不属于当前案件已绑定情景");
        return receipt.setState("COMMITTED").setScenarioId(scenario.getId().toString())
                .setScenarioNo(scenario.getScenarioNo()).setScenarioStatus(scenario.getScenarioStatus())
                .setMessage("已找到原计算结果");
    }

    /** 显式限定租户和工厂；请求号相同不能读取其他工厂或其他案件的结果。 */
    private PlnSchedulingScenarioEntity findRequest(PlnApsCaseDTO apsCase, String requestId) {
        return scenarioMapper.selectOne(new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                .eq(PlnSchedulingScenarioEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(PlnSchedulingScenarioEntity::getFactoryCode, apsCase.getFactoryCode())
                .eq(PlnSchedulingScenarioEntity::getRequestId, requestId));
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
        target.setExpectedFingerprint(source.getExpectedInputFingerprint());
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
}
