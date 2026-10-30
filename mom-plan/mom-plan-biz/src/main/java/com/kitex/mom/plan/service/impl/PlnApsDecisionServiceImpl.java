package com.kitex.mom.plan.service.impl;

import com.kitex.mom.plan.service.aps.PlnApsSourceRouting;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.*;
import com.kitex.mom.plan.entity.PlnApsA3ApprovalEntity;
import com.kitex.mom.plan.entity.PlnSchedulingOperationEntity;
import com.kitex.mom.plan.entity.PlnSchedulingScenarioEntity;
import com.kitex.mom.plan.entity.PlnSchedulingTaskEntity;
import com.kitex.mom.plan.mapper.PlnSchedulingScenarioMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingTaskMapper;
import com.kitex.mom.plan.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 智能 APS 案件决策实现。
 *
 * <p>A2 复用现有候选可行性和来源指纹校验；A3 在事务外先完成资源重采，使失效标记能够独立保留，
 * 再在同一事务内执行案件进入审批、现有 APS 正式锁定和案件发布完成。</p>
 */
@Service
@RequiredArgsConstructor
public class PlnApsDecisionServiceImpl implements PlnApsDecisionService {

    private static final Set<String> COMPARABLE_STATUSES =
            Set.of("EVALUATED", "SELECTED", "PUBLISHING", "PUBLISHED", "DISPATCHING", "EXECUTING", "COMPLETED");
    private static final Set<String> PUBLISH_REPLAY_STATUSES =
            Set.of("PUBLISHED", "DISPATCHING", "EXECUTING", "FAILED");

    private final PlnApsCaseService caseService;
    private final PlnApsA3ApprovalService approvalService;
    private final PlnApsReadinessService readinessService;
    private final PlnSchedulingAgentService schedulingAgentService;
    private final PlnSchedulingEngineService schedulingEngineService;
    private final PlnSchedulingScenarioMapper scenarioMapper;
    private final PlnSchedulingTaskMapper schedulingTaskMapper;
    private final PlnOperationSchedulingService operationSchedulingService;
    private final PlnApsDispatchService dispatchService;
    private final PlnApsFormalContractResolver formalContractResolver;
    private final PlatformTransactionManager transactionManager;

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

    private static String text(Long value) {
        return value == null ? null : value.toString();
    }

    private static boolean isFingerprint(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    public PlnApsDecisionDTO.Comparison compare(Long caseId) {
        PlnApsCaseDTO apsCase = caseService.detail(caseId);
        PlnApsSourceRouting.requireStandard(apsCase);
        require(COMPARABLE_STATUSES.contains(apsCase.getCaseStatus()) && StrUtil.isNotBlank(apsCase.getScenarioId()),
                "APS案件尚未形成可比较的多候选情景");
        PlanSchedulingAgentDTO.DecisionContext context = schedulingAgentService.decisionContext(
                scenarioQuery(apsCase, parseId(apsCase.getScenarioId(), "APS情景主键")));
        require(Objects.equals(context.getScenarioEvidence().getScenario().getScenario().getId(),
                parseId(apsCase.getScenarioId(), "APS情景主键")), "候选比较情景与案件绑定不一致");
        return new PlnApsDecisionDTO.Comparison().setApsCase(apsCase).setDecisionContext(context);
    }

    @Override
    public List<PlnSchedulingOperationEntity> candidateOperations(Long caseId, Long taskId) {
        PlnApsCaseDTO apsCase = caseService.detail(caseId);
        PlnApsSourceRouting.requireStandard(apsCase);
        require(COMPARABLE_STATUSES.contains(apsCase.getCaseStatus())
                && StrUtil.isNotBlank(apsCase.getScenarioId()), "当前案件尚无可读候选");
        PlnSchedulingTaskEntity task = schedulingTaskMapper.selectById(taskId);
        require(task != null && Objects.equals(task.getScenarioId(),
                        parseId(apsCase.getScenarioId(), "APS情景主键"))
                        && Objects.equals(task.getFactoryCode(), apsCase.getFactoryCode()),
                "候选不属于当前案件和实际工厂");
        List<PlnSchedulingOperationEntity> operations = operationSchedulingService
                .listTaskOperations(taskId, null, null);
        require(operations.stream().allMatch(item -> Objects.equals(item.getTaskId(), taskId)
                        && Objects.equals(item.getFactoryCode(), apsCase.getFactoryCode())),
                "候选工序存在跨案件或跨工厂记录");
        return operations;
    }

    @Override
    public PlnApsDecisionDTO.Comparison derive(Long caseId, PlnApsDecisionDTO.DeriveCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getRequestId())
                        && command.getRequestId().trim().length() <= 64
                        && isFingerprint(command.getExpectedCaseFingerprint())
                        && isFingerprint(command.getExpectedScenarioFingerprint())
                        && isFingerprint(command.getExpectedCandidateFingerprint())
                        && StrUtil.isNotBlank(command.getParentCandidateTaskId())
                        && StrUtil.isNotBlank(command.getReason())
                        && command.getReason().trim().length() <= 500
                        && command.getOperations() != null && !command.getOperations().isEmpty()
                        && command.getOperations().size() <= 100,
                "专业调整须提供有效请求号、案件和候选证据、原因以及1至100道工序变更");
        PlnApsCaseDTO before = caseService.detail(caseId);
        PlnApsSourceRouting.requireStandard(before);
        require("EVALUATED".equals(before.getCaseStatus())
                        && Objects.equals(before.getContextFingerprint(), command.getExpectedCaseFingerprint()),
                "只有未选案且未发布的当前APS案件允许专业调整");
        Long scenarioId = parseId(before.getScenarioId(), "APS情景主键");
        // 已完成同号请求只回放既有候选；真实载荷指纹仍由领域服务校验。
        // 不能先重采资源，否则首次成功后资源变化会使响应丢失的原样重试无法恢复。
        boolean replay = schedulingTaskMapper.selectOne(new LambdaQueryWrapper<PlnSchedulingTaskEntity>()
                .eq(PlnSchedulingTaskEntity::getScenarioId, scenarioId)
                .eq(PlnSchedulingTaskEntity::getFactoryCode, before.getFactoryCode())
                .eq(PlnSchedulingTaskEntity::getDerivationRequestId, command.getRequestId().trim())
                .last("LIMIT 1")) != null;
        if (!replay) {
            Long snapshotId = parseId(before.getResourceSnapshotId(), "APS资源快照主键");
            PlnApsResourceDTO.Snapshot snapshot = readinessService.recheck(caseId, snapshotId);
            require(!Boolean.TRUE.equals(snapshot.getStale()) && Boolean.TRUE.equals(snapshot.getComplete())
                            && "READY".equals(snapshot.getReadinessStatus())
                            && Objects.equals(snapshot.getResourceFingerprint(), before.getResourceFingerprint()),
                    StrUtil.blankToDefault(snapshot.getStaleReason(), "资源证据已失效，请重新采集后求解"));
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        PlnApsDecisionDTO.Comparison result = transaction.execute(status ->
                deriveInTransaction(caseId, command, replay));
        require(result != null, "专业调整事务未返回候选比较结果");
        return result;
    }

    private PlnApsDecisionDTO.Comparison deriveInTransaction(
            Long caseId, PlnApsDecisionDTO.DeriveCommand command, boolean replayOnly) {
        PlnApsCaseDTO apsCase = caseService.detail(caseId);
        PlnApsSourceRouting.requireStandard(apsCase);
        require("EVALUATED".equals(apsCase.getCaseStatus())
                        && Objects.equals(apsCase.getContextFingerprint(), command.getExpectedCaseFingerprint()),
                "只有未选案且未发布的当前APS案件允许专业调整");
        Long scenarioId = parseId(apsCase.getScenarioId(), "APS情景主键");
        PlnSchedulingScenarioEntity scenario = scenarioMapper.selectById(scenarioId);
        require(scenario != null && Objects.equals(scenario.getFactoryCode(), apsCase.getFactoryCode()),
                "案件绑定的情景不存在或工厂不一致");
        scenarioMapper.lock("APS_SCENARIO_SELECT:" + scenario.getScenarioNo());
        PlnSchedulingTaskEntity replay = schedulingTaskMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingTaskEntity>()
                        .eq(PlnSchedulingTaskEntity::getScenarioId, scenarioId)
                        .eq(PlnSchedulingTaskEntity::getFactoryCode, apsCase.getFactoryCode())
                        .eq(PlnSchedulingTaskEntity::getDerivationRequestId, command.getRequestId().trim())
                        .last("LIMIT 1"));
        require(!replayOnly || replay != null, "原派生请求已不存在，不能绕过资源复核创建新候选");
        if (replay == null) {
            PlanSchedulingAgentDTO.ScenarioEvidence current = scenarioEvidence(apsCase);
            require(Objects.equals(current.getScenarioFingerprint(), command.getExpectedScenarioFingerprint()),
                    "情景或候选排名已变化，请刷新专业工作台后调整");
            Long parentId = parseId(command.getParentCandidateTaskId(), "父候选任务主键");
            PlanSchedulingAgentDTO.CandidateEvidence parent = current.getCandidates().stream()
                    .filter(item -> Objects.equals(item.getTaskId(), parentId)).findFirst()
                    .orElseThrow(() -> new CheckedException("父候选不属于当前案件情景"));
            require(Objects.equals(parent.getCandidateFingerprint(), command.getExpectedCandidateFingerprint()),
                    "父候选工序或资源证据已变化，请刷新后调整");
        }
        schedulingEngineService.deriveScenarioCandidate(caseId, command);
        return compare(caseId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanSchedulingAgentDTO.ScenarioEvidence select(Long caseId, PlnApsDecisionDTO.SelectCommand command) {
        validateSelect(command);
        Long taskId = parseId(command.getCandidateTaskId(), "APS候选任务主键");
        PlnApsCaseDTO apsCase = caseService.detail(caseId);
        PlnApsSourceRouting.requireStandard(apsCase);
        PlanSchedulingAgentDTO.ScenarioEvidence evidence = scenarioEvidence(apsCase);
        PlnSchedulingScenarioEntity scenario = evidence.getScenario().getScenario();

        if ("SELECTED".equals(apsCase.getCaseStatus()) && Objects.equals(apsCase.getSelectedTaskId(), taskId.toString())) {
            require(Objects.equals(scenario.getSelectionRequestId(), command.getRequestId().trim()),
                    "案件已完成其他A2选案，禁止覆盖");
            require(Objects.equals(scenario.getSelectionReason(), command.getReason().trim()),
                    "同一A2选案请求号已对应不同选择原因");
            return evidence;
        }

        require("EVALUATED".equals(apsCase.getCaseStatus()), "只有完成求解评估的APS案件可以执行A2选案");
        require(Objects.equals(apsCase.getContextFingerprint(), command.getExpectedCaseFingerprint()),
                "APS案件上下文已变化，请刷新候选比较后重试");
        require(Objects.equals(evidence.getScenarioFingerprint(), command.getExpectedScenarioFingerprint()),
                "APS情景证据已变化，请刷新候选比较后重试");
        PlanSchedulingAgentDTO.CandidateEvidence candidate = evidence.getCandidates().stream()
                .filter(item -> Objects.equals(item.getTaskId(), taskId)).findFirst()
                .orElseThrow(() -> new CheckedException("所选候选不属于当前APS案件情景"));
        require(Boolean.TRUE.equals(candidate.getFeasible()), "存在硬阻断的候选不能执行A2选案");
        require(Objects.equals(candidate.getCandidateFingerprint(), command.getExpectedCandidateFingerprint()),
                "候选指标、工序或资源证据已变化，请刷新后重试");

        PlanSchedulingAgentDTO.ScenarioEvidence selected = schedulingAgentService.select(
                toSelectCommand(apsCase, scenario, taskId, command));
        caseService.bindSelectedCandidate(caseId, taskId, suffix(command.getRequestId(), "A2"),
                apsCase.getContextFingerprint(), command.getReason());
        return selected;
    }

    @Override
    public PlnApsDecisionDTO.PublishResult publish(Long caseId, PlnApsDecisionDTO.PublishCommand command) {
        validatePublish(command);
        PlnApsCaseDTO before = caseService.detail(caseId);
        PlnApsSourceRouting.requireStandard(before);
        if (PUBLISH_REPLAY_STATUSES.contains(before.getCaseStatus())) {
            TransactionTemplate replayTransaction = new TransactionTemplate(transactionManager);
            return replayTransaction.execute(status -> {
                PlnApsA3ApprovalEntity approval = approvalService.requireApproved(caseId, command, true);
                PlanSchedulingAgentDTO.ScenarioEvidence evidence = scenarioEvidence(before);
                PlanSchedulingAgentDTO.PublishReceipt receipt = schedulingAgentService.publish(
                        toPublishCommand(before, evidence.getScenario().getScenario(), command, approval));
                dispatchService.enqueue(caseId, receipt.getTaskId(), receipt.getPublishRequestFingerprint());
                return new PlnApsDecisionDTO.PublishResult().setApsCase(caseService.detail(caseId)).setReceipt(receipt);
            });
        }
        require("SELECTED".equals(before.getCaseStatus()), "只有完成A2选案的APS案件可以进入A3发布");
        require(Objects.equals(before.getContextFingerprint(), command.getExpectedCaseFingerprint()),
                "APS案件上下文已变化，请刷新后重新提交A3审批");
        Long snapshotId = parseId(before.getResourceSnapshotId(), "APS资源快照主键");
        PlnApsResourceDTO.Snapshot snapshot = readinessService.recheck(caseId, snapshotId);
        require(!Boolean.TRUE.equals(snapshot.getStale()) && Boolean.TRUE.equals(snapshot.getComplete())
                        && "READY".equals(snapshot.getReadinessStatus())
                        && Objects.equals(snapshot.getResourceFingerprint(), before.getResourceFingerprint()),
                StrUtil.blankToDefault(snapshot.getStaleReason(), "A3发布前资源证据复核未通过"));

        PlnApsFormalContractResolver.Resolution resolution = formalContractResolver.resolve(
                parseId(before.getSelectedTaskId(), "A2候选任务主键"), before.getFactoryCode());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        PlnApsDecisionDTO.PublishResult result = transaction.execute(
                status -> publishInTransaction(caseId, command, resolution));
        require(result != null, "APS正式发布事务未返回结果");
        return result;
    }

    private PlnApsDecisionDTO.PublishResult publishInTransaction(
            Long caseId, PlnApsDecisionDTO.PublishCommand command,
            PlnApsFormalContractResolver.Resolution resolution) {
        PlnApsCaseDTO current = caseService.detail(caseId);
        PlnApsSourceRouting.requireStandard(current);
        PlnApsA3ApprovalEntity approval = approvalService.requireApproved(caseId, command, false);
        require("SELECTED".equals(current.getCaseStatus())
                        && Objects.equals(current.getContextFingerprint(), command.getExpectedCaseFingerprint()),
                "A3发布前案件已变化，请重新送审");
        PlnApsCaseDTO contractBound = caseService.bindFormalPlanContract(caseId, resolution.policy(),
                resolution.contract(), suffix(command.getRequestId(), "CONTRACT"),
                current.getContextFingerprint(), "A3发布前冻结当前策略下的唯一正式计划合同");
        PlanSchedulingAgentDTO.ScenarioEvidence evidence = scenarioEvidence(current);
        PlnSchedulingScenarioEntity scenario = evidence.getScenario().getScenario();
        require(Objects.equals(evidence.getScenarioFingerprint(), command.getExpectedScenarioFingerprint()),
                "A3发布前情景、候选指标或工序证据已变化");
        require(Objects.equals(scenario.getSelectionFingerprint(), command.getExpectedSelectionFingerprint()),
                "A2选案事实已变化，原A3审批失效");
        require(Objects.equals(current.getSelectedTaskId(), text(scenario.getSelectedTaskId())),
                "案件A2候选与APS情景选案结果不一致");

        PlnApsCaseDTO publishing = advance(caseId, contractBound, "START_PUBLISH",
                suffix(command.getRequestId(), "A3START"), "进入异岗A3正式发布");
        PlanSchedulingAgentDTO.PublishReceipt receipt = schedulingAgentService.publish(
                toPublishCommand(contractBound, scenario, command, approval));
        PlnApsCaseDTO published = caseService.completePublish(caseId, suffix(command.getRequestId(), "A3RESULT"),
                publishing.getContextFingerprint(), "APS正式锁定成功：" + receipt.getScenarioNo());
        dispatchService.enqueue(caseId, receipt.getTaskId(), receipt.getPublishRequestFingerprint());
        approvalService.consume(approval);
        return new PlnApsDecisionDTO.PublishResult().setApsCase(caseService.detail(caseId)).setReceipt(receipt);
    }

    private PlnApsCaseDTO advance(Long caseId, PlnApsCaseDTO current, String action,
                                  String requestId, String reason) {
        PlnApsCaseCommand.Advance advance = new PlnApsCaseCommand.Advance();
        advance.setAction(action);
        advance.setRequestId(requestId);
        advance.setExpectedVersion(current.getWorkflowVersion());
        advance.setExpectedContextFingerprint(current.getContextFingerprint());
        advance.setReason(reason);
        return caseService.advance(caseId, advance);
    }

    private PlanSchedulingAgentDTO.ScenarioEvidence scenarioEvidence(PlnApsCaseDTO apsCase) {
        require(StrUtil.isNotBlank(apsCase.getScenarioId()), "APS案件尚未绑定求解情景");
        return schedulingAgentService.query(scenarioQuery(apsCase,
                parseId(apsCase.getScenarioId(), "APS情景主键")));
    }

    private PlanSchedulingAgentDTO.ScenarioQuery scenarioQuery(PlnApsCaseDTO apsCase, Long scenarioId) {
        PlanSchedulingAgentDTO.ScenarioQuery query = new PlanSchedulingAgentDTO.ScenarioQuery();
        query.setFactoryCode(apsCase.getFactoryCode());
        query.setScenarioId(scenarioId);
        return query;
    }

    private PlanSchedulingAgentDTO.SelectCommand toSelectCommand(
            PlnApsCaseDTO apsCase, PlnSchedulingScenarioEntity scenario, Long taskId,
            PlnApsDecisionDTO.SelectCommand source) {
        PlanSchedulingAgentDTO.SelectCommand target = new PlanSchedulingAgentDTO.SelectCommand();
        target.setFactoryCode(apsCase.getFactoryCode());
        target.setScenarioNo(scenario.getScenarioNo());
        target.setRequestId(source.getRequestId().trim());
        target.setCandidateTaskId(taskId);
        target.setExpectedScenarioFingerprint(source.getExpectedScenarioFingerprint());
        target.setExpectedCandidateFingerprint(source.getExpectedCandidateFingerprint());
        target.setReason(source.getReason().trim());
        return target;
    }

    private PlanSchedulingAgentDTO.PublishCommand toPublishCommand(
            PlnApsCaseDTO apsCase, PlnSchedulingScenarioEntity scenario,
            PlnApsDecisionDTO.PublishCommand source, PlnApsA3ApprovalEntity approval) {
        PlanSchedulingAgentDTO.PublishCommand target = new PlanSchedulingAgentDTO.PublishCommand();
        target.setFactoryCode(apsCase.getFactoryCode());
        target.setScenarioNo(scenario.getScenarioNo());
        target.setRequestId(source.getRequestId().trim());
        target.setSelectedTaskId(parseId(apsCase.getSelectedTaskId(), "A2候选任务主键"));
        target.setExpectedScenarioFingerprint(source.getExpectedScenarioFingerprint());
        target.setExpectedSelectionFingerprint(source.getExpectedSelectionFingerprint());
        target.setAgentApprovalId(approval.getId());
        target.setAgentApprovalNo(approval.getApprovalNo());
        target.setApplicantCode(approval.getApplicantCode());
        target.setApproverCode(approval.getApproverCode());
        return target;
    }

    private void validateSelect(PlnApsDecisionDTO.SelectCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getRequestId())
                        && command.getRequestId().trim().length() <= 64
                        && isFingerprint(command.getExpectedCaseFingerprint())
                        && StrUtil.isNotBlank(command.getCandidateTaskId())
                        && isFingerprint(command.getExpectedScenarioFingerprint())
                        && isFingerprint(command.getExpectedCandidateFingerprint())
                        && StrUtil.isNotBlank(command.getReason()) && command.getReason().trim().length() <= 500,
                "A2选案命令缺少有效案件、情景、候选证据或原因");
    }

    private void validatePublish(PlnApsDecisionDTO.PublishCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getRequestId())
                        && command.getRequestId().trim().length() <= 64
                        && isFingerprint(command.getExpectedCaseFingerprint())
                        && isFingerprint(command.getExpectedScenarioFingerprint())
                        && isFingerprint(command.getExpectedSelectionFingerprint())
                        && StrUtil.isNotBlank(command.getApprovalId()),
                "A3发布须携带当前PLAN审批记录与冻结证据");
    }
}
