package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmRouteMaterialAssignmentQueryDTO;
import com.kitex.mom.mdm.entity.MdmProcessProcedureMatEntity;
import com.kitex.mom.mdm.feign.RemoteProcessRouteService;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesApsLocalScheduleDTO;
import com.kitex.mom.plan.dto.PlanMrpShortageAnalysisDTO;
import com.kitex.mom.plan.dto.PlanMrpShortageLineDTO;
import com.kitex.mom.plan.dto.PlanMrpShortageQueryDTO;
import com.kitex.mom.plan.dto.PlanShortageLocalReplanDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlanMatMrpService;
import com.kitex.mom.plan.service.PlnOperationSchedulingService;
import com.kitex.mom.plan.service.PlnShortageLocalReplanService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 缺料局部重排领域服务实现。
 *
 * <p>业务流程分为七个阶段：读取缺料与供应证据、识别直接及下游受影响工序、生成三个有限产能候选、
 * 评估并选择候选、发布到 APS/MES、取消未发布方案、受控回退已发布方案。实现中保存的各类指纹用于
 * 证明 MRP、正式排程和 MES 执行事实在跨步骤审批期间没有发生变化。</p>
 *
 * <p>该实现是跨聚合事务编排器，不是单表持久化服务，因此不继承 {@code ServiceImpl}。</p>
 */
@Service
@RequiredArgsConstructor
public class PlnShortageLocalReplanServiceImpl implements PlnShortageLocalReplanService {

    /**
     * 默认排程冻结区：2 小时。
     */
    private static final int DEFAULT_FREEZE_MINUTES = 120;

    /**
     * 默认允许的最大完工延期：7 天。
     */
    private static final int DEFAULT_MAX_DELAY_MINUTES = 7 * 24 * 60;

    /**
     * 策略参数上限：366 天，防止异常输入造成超大排程窗口。
     */
    private static final int MAX_POLICY_MINUTES = 366 * 24 * 60;

    /**
     * 候选方案默认有效期：24 小时。
     */
    private static final int PROPOSAL_VALID_HOURS = 24;

    private final PlnShortageReplanCaseMapper caseMapper;
    private final PlnShortageReplanLineMapper lineMapper;
    private final PlnShortageReplanOperationMapper auditMapper;
    private final PlnInsertionReplanCaseMapper insertionCaseMapper;
    private final PlnInsertionReplanIssueMapper insertionIssueMapper;
    private final PlanMatMrpMapper mrpMapper;
    private final PlnIssueMapper issueMapper;
    private final PlnSchedulingTaskMapper taskMapper;
    private final PlnSchedulingScenarioMapper scenarioMapper;
    private final PlnSchedulingTaskDetailMapper taskDetailMapper;
    private final PlnSchedulingOperationMapper schedulingOperationMapper;
    private final PlnOperationSchedulingService operationSchedulingService;
    private final PlanMatMrpService planMatMrpService;
    private final RemoteMesTaskService remoteMesTaskService;
    private final RemoteProcessRouteService remoteProcessRouteService;
    private final ObjectMapper objectMapper;

    @Override
    public Page<PlnShortageReplanCaseEntity> page(SearchPage page, PlanShortageLocalReplanDTO.Query query) {
        LocalDateTime now = LocalDateTime.now();
        caseMapper.update(null, new LambdaUpdateWrapper<PlnShortageReplanCaseEntity>()
                .eq(PlnShortageReplanCaseEntity::getStatus, "PROPOSED")
                .le(PlnShortageReplanCaseEntity::getExpiresTime, now)
                .set(PlnShortageReplanCaseEntity::getStatus, "EXPIRED"));
        scenarioMapper.update(null, new LambdaUpdateWrapper<PlnSchedulingScenarioEntity>()
                .eq(PlnSchedulingScenarioEntity::getTriggerType, "SHORTAGE_LOCAL_REPLAN")
                .eq(PlnSchedulingScenarioEntity::getScenarioStatus, "EVALUATED")
                .le(PlnSchedulingScenarioEntity::getExpiresTime, now)
                .set(PlnSchedulingScenarioEntity::getScenarioStatus, "EXPIRED"));
        PlanShortageLocalReplanDTO.Query q = query == null ? new PlanShortageLocalReplanDTO.Query() : query;
        Page<PlnShortageReplanCaseEntity> result = caseMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<PlnShortageReplanCaseEntity>()
                .like(StrUtil.isNotBlank(q.getCaseNo()), PlnShortageReplanCaseEntity::getCaseNo, q.getCaseNo())
                .like(StrUtil.isNotBlank(q.getIssueNo()), PlnShortageReplanCaseEntity::getIssueNo, q.getIssueNo())
                .like(StrUtil.isNotBlank(q.getMaterialCode()), PlnShortageReplanCaseEntity::getMaterialCode, q.getMaterialCode())
                .eq(StrUtil.isNotBlank(q.getStatus()), PlnShortageReplanCaseEntity::getStatus, q.getStatus())
                .orderByDesc(PlnShortageReplanCaseEntity::getProposedTime));
        result.getRecords().forEach(this::enrichCandidate);
        return result;
    }

    @Override
    public PlanShortageLocalReplanDTO.Proposal detail(Long caseId) {
        PlnShortageReplanCaseEntity entity = requireCase(caseId, false);
        requireFactory(entity.getFactoryCode());
        PlanShortageLocalReplanDTO.Proposal result = new PlanShortageLocalReplanDTO.Proposal();
        BeanUtils.copyProperties(entity, result);
        result.setLines(lineMapper.selectList(new LambdaQueryWrapper<PlnShortageReplanLineEntity>()
                .eq(PlnShortageReplanLineEntity::getCaseId, caseId)
                .orderByAsc(PlnShortageReplanLineEntity::getOperationSeq)));
        enrichCandidate(result);
        return result;
    }

    @Override
    public PlanShortageLocalReplanDTO.Scenario scenario(Long scenarioId) {
        require(scenarioId != null, "缺料重排情景不能为空");
        PlnSchedulingScenarioEntity header = scenarioMapper.selectById(scenarioId);
        require(header != null && "SHORTAGE_LOCAL_REPLAN".equals(header.getTriggerType()), "缺料重排情景不存在");
        requireFactory(header.getFactoryCode());
        List<PlanShortageLocalReplanDTO.Proposal> candidates = caseMapper.selectList(
                        new LambdaQueryWrapper<PlnShortageReplanCaseEntity>()
                                .eq(PlnShortageReplanCaseEntity::getScenarioId, scenarioId)
                                .orderByAsc(PlnShortageReplanCaseEntity::getId)).stream()
                .map(item -> detail(item.getId())).sorted(Comparator.comparing(
                        PlanShortageLocalReplanDTO.Proposal::getCandidateRank,
                        Comparator.nullsLast(Integer::compareTo))).toList();
        return new PlanShortageLocalReplanDTO.Scenario().setScenario(header).setCandidates(candidates);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanShortageLocalReplanDTO.Scenario propose(PlanShortageLocalReplanDTO.ProposeCommand command) {
        // 阶段 1：规范请求参数并建立永久幂等边界，避免同一请求号生成不同候选。
        require(command != null && StrUtil.isNotBlank(command.getRequestId()), "局部重排请求号不能为空");
        require(command.getRequestId().trim().length() <= 48, "局部重排请求号不能超过48位");
        if (StrUtil.isBlank(command.getFactoryCode())) {
            command.setFactoryCode(FactoryContextHolder.getFactoryCode());
        }
        require(StrUtil.isNotBlank(command.getFactoryCode()) && command.getMrpId() != null,
                "当前工厂和MRP记录不能为空");
        require(command.getExpectedSupplyTime() != null && command.getExpectedSupplyTime().isAfter(LocalDateTime.now()),
                "预计齐料时刻必须晚于当前时刻");
        List<String> directCodes = normalizeCodes(command.getAffectedProcessCodes());
        int freezeMinutes = bounded(command.getFreezeMinutes(), DEFAULT_FREEZE_MINUTES, 30, 7 * 24 * 60,
                "冻结区分钟数必须在30分钟到7天之间");
        int maxDelayMinutes = bounded(command.getMaxDelayMinutes(), DEFAULT_MAX_DELAY_MINUTES, 1,
                MAX_POLICY_MINUTES, "最大允许延期必须在1分钟到366天之间");
        require(StrUtil.isNotBlank(command.getReason()), "局部重排必须填写业务原因");
        requireFactory(command.getFactoryCode());
        command.setAffectedProcessCodes(directCodes);
        command.setFreezeMinutes(freezeMinutes);
        command.setMaxDelayMinutes(maxDelayMinutes);
        String requestSnapshot = json(command);
        String requestFingerprint = hash(requestSnapshot);
        scenarioMapper.lock("SHORTAGE_SCENARIO_PROPOSE:" + command.getRequestId().trim());
        PlnSchedulingScenarioEntity duplicate = scenarioMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                        .eq(PlnSchedulingScenarioEntity::getRequestId, command.getRequestId().trim()));
        if (duplicate != null) {
            require("SHORTAGE_LOCAL_REPLAN".equals(duplicate.getTriggerType())
                            && Objects.equals(duplicate.getRequestFingerprint(), requestFingerprint),
                    "方案幂等号已被不同请求使用");
            return scenario(duplicate.getId());
        }

        // 阶段 2：锁定缺料与计划事实，并用 MRP 正式供应证据确定可重排的最早时刻。
        PlanMatMrpEntity mrp = lockMrp(command.getMrpId());
        PlnIssueEntity issue = command.getIssueId() == null ? lockIssueForMrp(mrp) : lockIssue(command.getIssueId());
        command.setIssueId(issue.getId());
        validateScope(mrp, issue, command.getFactoryCode());
        ensureNoInsertionProposal(issue.getId());
        SupplyEvidence supplyEvidence = supplyEvidence(mrp, command.getExpectedSupplyTime());
        LocalDateTime freezeUntil = LocalDateTime.now().plusMinutes(freezeMinutes);
        require(command.getExpectedSupplyTime().isAfter(freezeUntil), "预计齐料时刻仍在冻结区内，禁止通过排程掩盖现场缺料");
        // 阶段 3：以 MDM 工序投料关系识别直接影响工序，再沿工艺前置关系计算下游闭包。
        List<PlnSchedulingOperationEntity> originals = lockedOperations(issue);
        directCodes = PlnFrozenRouteNodeIdentity.selection(originals, directCodes, command.getAffectedSourceRouteNodeCodes());
        List<String> mappedDirectCodes = resolveDirectAffected(mrp, originals);
        require(!mappedDirectCodes.isEmpty(), "MDM未维护该缺料物料的工序投料映射，不能确定直接影响工序");
        if (directCodes.isEmpty()) {
            directCodes = mappedDirectCodes;
        } else {
            require(new LinkedHashSet<>(directCodes).equals(new LinkedHashSet<>(mappedDirectCodes)),
                    "手工指定的直接影响工序与MDM工序投料映射不一致，禁止缩小影响范围");
        }
        Map<String, PlnSchedulingOperationEntity> originalByCode = uniqueByProcess(originals);
        require(originalByCode.keySet().containsAll(directCodes), "直接受影响工序与APS正式排程不一致");
        Set<String> impactedCodes = downstreamClosure(originals, directCodes);
        for (String code : impactedCodes) {
            PlnSchedulingOperationEntity operation = originalByCode.get(code);
            require(operation != null && operation.getScheduledStartTime() != null, "受影响工序缺少正式开始时间");
            require(operation.getScheduledStartTime().isAfter(freezeUntil),
                    "工序[" + code + "]已进入排程冻结区，必须现场处置，禁止自动移动");
        }

        // 阶段 4：冻结 APS/MES 来源事实，后续选案和发布必须回传并重新验证这些指纹。
        MesApsLocalScheduleDTO.StateBundle mesBefore = mesStates(issue);
        PlnFrozenRouteNodeIdentity.correspondence(originals, mesBefore);
        validateMesMovable(mesBefore, impactedCodes);
        PlnSchedulingTaskEntity originalTask = taskMapper.selectById(issue.getLockTaskId());
        require(originalTask != null && "official".equalsIgnoreCase(originalTask.getMode()), "原正式排产任务不存在");
        String mrpFingerprint = supplyEvidence.fingerprint();
        String originalFingerprint = scheduleFingerprint(originals);
        MomUser user = currentUser();
        LocalDateTime now = LocalDateTime.now();
        String sourceFingerprint = hash(mrpFingerprint + '|' + originalFingerprint + '|'
                + mesBefore.getExecutionFingerprint());
        PlnSchedulingScenarioEntity header = new PlnSchedulingScenarioEntity();
        header.setScenarioNo("SRS-" + IdUtil.getSnowflakeNextIdStr());
        header.setRequestId(command.getRequestId().trim());
        header.setRequestFingerprint(requestFingerprint);
        header.setTriggerType("SHORTAGE_LOCAL_REPLAN");
        header.setCycleType("EVENT");
        header.setScheduleDate(command.getExpectedSupplyTime().toLocalDate());
        header.setPlanNum(1);
        header.setSourceIssueIdsSnapshot(json(List.of(issue.getId())));
        header.setSourceFingerprint(sourceFingerprint);
        header.setRuleCode("SHORTAGE_LOCAL_REPLAN");
        header.setRuleVersion(1);
        header.setScenarioStatus("EVALUATING");
        header.setCandidateCount(3);
        header.setExpiresTime(now.plusHours(PROPOSAL_VALID_HOURS));
        header.setFactoryCode(command.getFactoryCode());
        require(scenarioMapper.insert(header) == 1, "缺料多候选情景保存失败");

        // 阶段 5：使用三个不同业务目标生成有限产能候选，统一计算可行性指标并排名。
        List<ShortageCandidateProfile> profiles = List.of(
                new ShortageCandidateProfile("RECOVERY", "最早恢复", "RECOVERY_FIRST", "DELIVERY_FIRST"),
                new ShortageCandidateProfile("STABILITY", "最小扰动", "MINIMUM_DISRUPTION", "BALANCED_GLOBAL"),
                new ShortageCandidateProfile("CHANGEOVER", "换型稳定", "CHANGEOVER_STABLE", "CHANGEOVER_FIRST"));
        List<ShortageCandidateBuild> builds = new ArrayList<>();
        for (ShortageCandidateProfile profile : profiles) {
            builds.add(buildCandidate(header, profile, command, requestSnapshot, mrp, issue, originalTask,
                    originals, directCodes, impactedCodes, mesBefore, freezeUntil, maxDelayMinutes,
                    mrpFingerprint, originalFingerprint, user, now));
        }
        rankCandidates(builds);
        header.setScenarioStatus("EVALUATED");
        require(scenarioMapper.updateById(header) == 1, "缺料多候选情景评估状态保存失败");
        return scenario(header.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanShortageLocalReplanDTO.Scenario select(PlanShortageLocalReplanDTO.SelectCommand command) {
        // 选案只改变候选状态，不修改正式 APS 排程，也不向 MES 下发。
        require(command != null && StrUtil.isNotBlank(command.getRequestId()) && command.getCandidateCaseId() != null
                        && StrUtil.isNotBlank(command.getExpectedSourceFingerprint()) && StrUtil.isNotBlank(command.getReason()),
                "候选选择参数不完整");
        PlnShortageReplanCaseEntity selectedCase = requireCase(command.getCandidateCaseId(), false);
        require(selectedCase.getScenarioId() != null, "历史单方案不需要候选选择");
        scenarioMapper.lock("SHORTAGE_SCENARIO_SELECT:" + selectedCase.getScenarioId());
        PlnSchedulingScenarioEntity header = scenarioMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                        .eq(PlnSchedulingScenarioEntity::getId, selectedCase.getScenarioId()).last("FOR UPDATE"));
        require(header != null && "SHORTAGE_LOCAL_REPLAN".equals(header.getTriggerType()), "缺料多候选情景不存在");
        String selectionFingerprint = hash(command.getRequestId().trim() + '|' + command.getCandidateCaseId()
                + '|' + command.getExpectedSourceFingerprint() + '|' + command.getReason().trim());
        if (Set.of("SELECTED", "PUBLISHED").contains(header.getScenarioStatus())) {
            require(Objects.equals(header.getSelectionRequestId(), command.getRequestId().trim())
                            && Objects.equals(header.getSelectionFingerprint(), selectionFingerprint),
                    "该情景已完成候选选择，不允许覆盖审计结论");
            return scenario(header.getId());
        }
        require("EVALUATED".equals(header.getScenarioStatus()), "当前情景状态不允许选择候选");
        require(header.getExpiresTime() != null && header.getExpiresTime().isAfter(LocalDateTime.now()),
                "多候选情景已过期，必须重新预演");
        require(Objects.equals(header.getSourceFingerprint(), command.getExpectedSourceFingerprint()), "情景来源指纹不一致");
        require(Objects.equals(selectedCase.getScenarioId(), header.getId()) && "PROPOSED".equals(selectedCase.getStatus()),
                "候选不属于当前可选择情景");
        List<PlnShortageReplanCaseEntity> cases = caseMapper.selectList(
                new LambdaQueryWrapper<PlnShortageReplanCaseEntity>()
                        .eq(PlnShortageReplanCaseEntity::getScenarioId, header.getId()).last("FOR UPDATE"));
        require(cases.size() == header.getCandidateCount(), "情景候选数量不完整");
        List<PlnSchedulingTaskEntity> tasks = taskMapper.selectList(new LambdaQueryWrapper<PlnSchedulingTaskEntity>()
                .eq(PlnSchedulingTaskEntity::getScenarioId, header.getId()));
        PlnSchedulingTaskEntity selectedTask = tasks.stream()
                .filter(item -> Objects.equals(item.getId(), selectedCase.getProposalTaskId())).findFirst()
                .orElseThrow(() -> new CheckedException("候选任务不属于当前情景"));
        require(Boolean.TRUE.equals(selectedTask.getCandidateFeasible()), "存在硬阻断的候选不能进入发布链");
        cases.forEach(item -> {
            item.setStatus(Objects.equals(item.getId(), selectedCase.getId()) ? "SELECTED" : "SUPERSEDED");
            require(caseMapper.updateById(item) == 1, "候选方案选择状态保存失败");
        });
        tasks.forEach(item -> {
            boolean selected = Objects.equals(item.getId(), selectedTask.getId());
            item.setSelectedCandidate(selected);
            if (!selected) {
                item.setTaskStatus("SUPERSEDED");
            }
            require(taskMapper.updateById(item) == 1, "候选任务选择状态保存失败");
        });
        header.setSelectedTaskId(selectedTask.getId());
        header.setSelectionRequestId(command.getRequestId().trim());
        header.setSelectionFingerprint(selectionFingerprint);
        header.setSelectionReason(command.getReason().trim());
        header.setSelectedBy(userCode(currentUser()));
        header.setSelectedTime(LocalDateTime.now());
        header.setScenarioStatus("SELECTED");
        require(scenarioMapper.updateById(header) == 1, "候选选择审计保存失败");
        audit(selectedCase, command.getRequestId(), "SELECT", "PROPOSED", "SELECTED",
                "从三个有限产能候选中选择[" + selectedTask.getCandidateName() + "]：" + command.getReason().trim());
        return scenario(header.getId());
    }

    /**
     * 生成并持久化一个候选策略，包括仿真任务、工序差异、指标快照和不可变证据指纹。
     */
    private ShortageCandidateBuild buildCandidate(PlnSchedulingScenarioEntity header,
                                                  ShortageCandidateProfile profile,
                                                  PlanShortageLocalReplanDTO.ProposeCommand command,
                                                  String requestSnapshot, PlanMatMrpEntity mrp,
                                                  PlnIssueEntity issue, PlnSchedulingTaskEntity originalTask,
                                                  List<PlnSchedulingOperationEntity> originals,
                                                  List<String> directCodes, Set<String> impactedCodes,
                                                  MesApsLocalScheduleDTO.StateBundle mesBefore,
                                                  LocalDateTime freezeUntil, int maxDelayMinutes,
                                                  String mrpFingerprint, String originalFingerprint,
                                                  MomUser user, LocalDateTime now) {
        PlnSchedulingTaskEntity proposalTask = new PlnSchedulingTaskEntity();
        BeanUtils.copyProperties(originalTask, proposalTask, "id", "createTime", "updateTime", "createBy", "updateBy");
        proposalTask.setId(null);
        proposalTask.setTaskNo("LRP-" + IdUtil.getSnowflakeNextIdStr());
        proposalTask.setTriggerType("SHORTAGE_LOCAL_REPLAN");
        proposalTask.setMode("simulate");
        proposalTask.setTaskStatus("EVALUATING");
        proposalTask.setScheduleDate(command.getExpectedSupplyTime().toLocalDate());
        proposalTask.setPlanCount(1);
        proposalTask.setScenarioId(header.getId());
        proposalTask.setCandidateCode(profile.code());
        proposalTask.setCandidateName(profile.name());
        proposalTask.setSequenceStrategy(profile.sequenceStrategy());
        proposalTask.setCrossPriorityWindowDays(0);
        proposalTask.setObjectiveWeightsSnapshot(json(Map.of("localReplanStrategy", profile.solverStrategy(),
                "objective", profile.name(), "hardConstraintsRelaxed", false)));
        proposalTask.setSelectedCandidate(false);
        proposalTask.setRemark("缺料局部重排情景" + header.getScenarioNo() + "候选[" + profile.name()
                + "]，原任务=" + originalTask.getTaskNo() + "，MRP=" + mrp.getId());
        proposalTask.setFactoryCode(command.getFactoryCode());
        require(taskMapper.insert(proposalTask) == 1, "局部重排候选任务保存失败");

        PlnIssueEntity previewIssue = new PlnIssueEntity();
        BeanUtils.copyProperties(issue, previewIssue);
        previewIssue.setMaterialAvailableDate(command.getExpectedSupplyTime().toLocalDate());
        previewIssue.setMaterialAvailableTime(command.getExpectedSupplyTime());
        operationSchedulingService.previewLocalReplan(proposalTask.getId(), issue.getLockTaskId(), previewIssue,
                command.getExpectedSupplyTime(), profile.solverStrategy());
        List<PlnSchedulingOperationEntity> candidates = schedulingOperationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getTaskId, proposalTask.getId())
                        .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq));
        PlnFrozenRouteNodeIdentity.replacementSet(originals, candidates);
        Map<String, PlnSchedulingOperationEntity> candidateByCode = uniqueByProcess(candidates);
        require(candidateByCode.keySet().containsAll(impactedCodes), "候选排程未覆盖全部受影响工序");
        for (String code : directCodes)
            require(!candidateByCode.get(code).getScheduledStartTime()
                    .isBefore(command.getExpectedSupplyTime()), "直接受影响工序[" + code + "]候选开工早于预计齐料时刻");
        List<PlnSchedulingOperationEntity> moved = impactedCodes.stream().map(candidateByCode::get)
                .sorted(Comparator.comparing(PlnSchedulingOperationEntity::getOperationSeq)).toList();
        Map<String, PlnSchedulingOperationEntity> impactedCandidates = moved.stream()
                .collect(Collectors.toMap(PlnFrozenRouteNodeIdentity::key, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        operationSchedulingService.validateLocalReplan(issue, effectiveOperations(originals, impactedCandidates),
                issue.getLockTaskId(), issue.getId());
        LocalDateTime originalCompletion = originals.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElseThrow();
        LocalDateTime proposedCompletion = originals.stream().map(item -> impactedCodes.contains(PlnFrozenRouteNodeIdentity.key(item))
                        ? candidateByCode.get(PlnFrozenRouteNodeIdentity.key(item)).getScheduledEndTime() : item.getScheduledEndTime())
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElseThrow();
        long completionDelay = Math.max(0, Duration.between(originalCompletion, proposedCompletion).toMinutes());
        boolean policyFeasible = completionDelay <= maxDelayMinutes;
        long maxShift = maximumShift(originals, candidateByCode, impactedCodes);
        long totalShift = totalShift(originals, candidateByCode, impactedCodes);
        int resourceChanges = resourceChanges(originals, candidateByCode, impactedCodes);
        long changeoverMinutes = moved.stream().map(PlnSchedulingOperationEntity::getChangeoverMinutes)
                .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("completionDelayMinutes", completionDelay);
        metrics.put("maximumShiftMinutes", maxShift);
        metrics.put("totalShiftMinutes", totalShift);
        metrics.put("resourceChangeCount", resourceChanges);
        metrics.put("changeoverMinutes", changeoverMinutes);
        metrics.put("deliveryRisk", deliveryRisk(issue, proposedCompletion));
        metrics.put("movedOperationCount", moved.size());
        metrics.put("maximumAllowedDelayMinutes", maxDelayMinutes);
        metrics.put("policyFeasible", policyFeasible);
        proposalTask.setCandidateMetricsSnapshot(json(metrics));
        proposalTask.setCandidateFeasible(policyFeasible);
        proposalTask.setCandidateScore(policyFeasible
                ? candidateBaseScore(completionDelay, totalShift, resourceChanges, changeoverMinutes)
                : BigDecimal.ZERO.setScale(2));
        proposalTask.setCandidateRank(1);
        proposalTask.setEvaluatedTime(LocalDateTime.now());
        proposalTask.setTaskStatus("SUCCESS");
        require(taskMapper.updateById(proposalTask) == 1, "局部重排候选指标保存失败");

        String proposalFingerprint = hash(mrpFingerprint + '|' + originalFingerprint + '|'
                + mesBefore.getExecutionFingerprint() + '|' + scheduleFingerprint(moved));
        PlnShortageReplanCaseEntity entity = new PlnShortageReplanCaseEntity()
                .setCaseNo("SRC-" + IdUtil.getSnowflakeNextIdStr())
                .setRequestId(command.getRequestId().trim() + ':' + profile.code())
                .setRequestSnapshot(requestSnapshot).setMrpId(mrp.getId()).setIssueId(issue.getId())
                .setIssueNo(issue.getIssueNo()).setMaterialCode(mrp.getMatCode()).setMaterialName(mrp.getMatName())
                .setOriginalTaskId(issue.getLockTaskId()).setProposalTaskId(proposalTask.getId()).setScenarioId(header.getId())
                .setExpectedSupplyTime(command.getExpectedSupplyTime()).setAffectedProcessCodes(directCodes.stream().map(code -> uniqueByProcess(originals).get(code).getProcessCode()).distinct().collect(Collectors.joining(",")))
                .setFreezeUntil(freezeUntil).setMaxDelayMinutes(maxDelayMinutes).setDirectAffectedCount(directCodes.size())
                .setMovedOperationCount(moved.size()).setMaximumShiftMinutes(maxShift)
                .setOriginalCompletionTime(originalCompletion).setProposedCompletionTime(proposedCompletion)
                .setDeliveryRisk(deliveryRisk(issue, proposedCompletion)).setMesExecutionFingerprint(mesBefore.getExecutionFingerprint())
                .setMrpEvidenceFingerprint(mrpFingerprint).setOriginalScheduleFingerprint(originalFingerprint)
                .setOriginalScheduleSnapshot(json(originals)).setProposedScheduleSnapshot(json(moved))
                .setProposalFingerprint(proposalFingerprint).setStatus("PROPOSED").setReason(command.getReason().trim())
                .setApplicantCode(userCode(user)).setApplicantName(userName(user)).setProposedTime(now)
                .setExpiresTime(now.plusHours(PROPOSAL_VALID_HOURS)).setFactoryCode(command.getFactoryCode());
        require(caseMapper.insert(entity) == 1, "局部重排方案保存失败");
        saveCandidateLines(entity, originals, candidateByCode, directCodes, impactedCodes, mesBefore, freezeUntil);
        saveProposalDetail(proposalTask, previewIssue, moved);
        audit(entity, entity.getRequestId(), "PROPOSE", null, "PROPOSED", "候选[" + profile.name() + "]：缺料["
                + mrp.getMatCode() + "]影响" + directCodes.size() + "道工序，联动移动" + moved.size()
                + "道，完工延期" + completionDelay + "分钟"
                + (policyFeasible ? "" : "，超过策略上限，不可选择"));
        return new ShortageCandidateBuild(entity, proposalTask, completionDelay, totalShift, resourceChanges, changeoverMinutes);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanShortageLocalReplanDTO.PublishReceipt publish(PlanShortageLocalReplanDTO.PublishCommand command) {
        // 发布采用“先重新核验全部证据，再同步 MES，最后写回 APS”的顺序。
        require(command != null && command.getCaseId() != null && StrUtil.isNotBlank(command.getRequestId()), "发布参数不完整");
        require(StrUtil.isNotBlank(command.getExpectedProposalFingerprint())
                && StrUtil.isNotBlank(command.getExpectedMesExecutionFingerprint()), "发布必须携带方案和MES执行指纹");
        caseMapper.lockRequest("PUBLISH:" + command.getRequestId().trim());
        PlnShortageReplanCaseEntity byRequest = caseMapper.selectOne(new LambdaQueryWrapper<PlnShortageReplanCaseEntity>()
                .eq(PlnShortageReplanCaseEntity::getPublishRequestId, command.getRequestId().trim()));
        if (byRequest != null) {
            return receipt(byRequest, byRequest.getMesPublishedFingerprint());
        }
        PlnShortageReplanCaseEntity entity = requireCase(command.getCaseId(), true);
        requireFactory(entity.getFactoryCode());
        String beforeStatus = entity.getStatus();
        require(Objects.equals(entity.getFactoryCode(), command.getFactoryCode())
                        && Objects.equals(entity.getMrpId(), command.getMrpId())
                        && Objects.equals(entity.getIssueNo(), command.getIssueNo()),
                "发布命令的工厂、MRP或计划范围与方案不一致");
        if (entity.getScenarioId() == null) {
            require("PROPOSED".equals(entity.getStatus()), "仅待发布方案可以正式发布");
        } else {
            PlnSchedulingScenarioEntity header = scenarioMapper.selectOne(
                    new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                            .eq(PlnSchedulingScenarioEntity::getId, entity.getScenarioId()).last("FOR UPDATE"));
            require(header != null && "SELECTED".equals(header.getScenarioStatus())
                    && Objects.equals(header.getSelectedTaskId(), entity.getProposalTaskId())
                    && "SELECTED".equals(entity.getStatus()), "多候选情景必须先选择且只能发布被选方案");
        }
        require(entity.getExpiresTime() != null && entity.getExpiresTime().isAfter(LocalDateTime.now()), "方案已过期，必须重新预演");
        require(Objects.equals(entity.getProposalFingerprint(), command.getExpectedProposalFingerprint()), "方案指纹不一致");
        require(Objects.equals(entity.getMesExecutionFingerprint(), command.getExpectedMesExecutionFingerprint()), "审批冻结的MES指纹不一致");
        MomUser user = currentUser();
        require(!Objects.equals(entity.getApplicantCode(), userCode(user)), "A3局部重排禁止申请人自批并发布");

        // 审批可能跨越较长时间，必须重新读取 MRP、正式排程和 MES，任何变化都会使旧方案失效。
        PlanMatMrpEntity mrp = lockMrp(entity.getMrpId());
        PlnIssueEntity issue = lockIssue(entity.getIssueId());
        validateScope(mrp, issue, entity.getFactoryCode());
        List<PlnSchedulingOperationEntity> originals = lockedOperations(issue);
        require(Objects.equals(entity.getMrpEvidenceFingerprint(),
                supplyEvidence(mrp, entity.getExpectedSupplyTime()).fingerprint()), "MRP或正式供应事实已变化，旧方案失效");
        require(Objects.equals(entity.getOriginalScheduleFingerprint(), scheduleFingerprint(originals)), "正式排程已变化，旧方案失效");
        List<PlnShortageReplanLineEntity> lines = caseLines(entity.getId());
        List<PlnSchedulingOperationEntity> candidates = proposalOperations(entity, lines);
        String currentProposalFp = hash(entity.getMrpEvidenceFingerprint() + '|' + entity.getOriginalScheduleFingerprint()
                + '|' + entity.getMesExecutionFingerprint() + '|' + scheduleFingerprint(candidates));
        require(Objects.equals(entity.getProposalFingerprint(), currentProposalFp), "候选工序快照已变化，旧审批失效");
        Map<String, PlnSchedulingOperationEntity> candidateByCode = uniqueByProcess(candidates);
        operationSchedulingService.validateLocalReplan(issue, effectiveOperations(originals, candidateByCode),
                entity.getOriginalTaskId(), entity.getIssueId());

        MesApsLocalScheduleDTO.StateBundle mesBefore = mesStates(issue);
        PlnFrozenRouteNodeIdentity.correspondence(originals, mesBefore);
        require(Objects.equals(entity.getMesExecutionFingerprint(), mesBefore.getExecutionFingerprint()), "MES执行或派工事实已变化，旧方案失效");
        Set<String> movedCodes = candidates.stream().map(PlnFrozenRouteNodeIdentity::key).collect(Collectors.toSet());
        validateMesMovable(mesBefore, movedCodes);
        // MES 是外部系统，无法参与本地数据库事务；本地写回失败时通过补偿命令恢复 MES 原排程。
        MesApsLocalScheduleDTO.ApplyReceipt mesReceipt = applyMes(command.getRequestId() + ":MES", entity,
                mesBefore.getExecutionFingerprint(), candidates);
        List<PlnSchedulingOperationEntity> mesCompensation = originals.stream()
                .filter(item -> movedCodes.contains(PlnFrozenRouteNodeIdentity.key(item))).map(this::detachedOperation).toList();
        try {
            for (PlnSchedulingOperationEntity original : originals) {
                PlnSchedulingOperationEntity candidate = candidateByCode.get(PlnFrozenRouteNodeIdentity.key(original));
                if (candidate != null) {
                    copySchedule(candidate, original);
                    require(schedulingOperationMapper.updateById(original) == 1, "正式工序排程写回失败");
                }
            }
            updateFormalSummary(issue, originals, "缺料局部重排已复核发布");
            entity.setStatus("PUBLISHED").setPublishRequestId(command.getRequestId().trim())
                    .setPublisherCode(userCode(user)).setPublisherName(userName(user)).setPublishedTime(LocalDateTime.now())
                    .setMesPublishedFingerprint(mesReceipt.getAfterExecutionFingerprint());
            require(caseMapper.updateById(entity) == 1, "局部重排发布状态更新失败");
            if (entity.getScenarioId() != null) {
                PlnSchedulingScenarioEntity header = scenarioMapper.selectById(entity.getScenarioId());
                header.setScenarioStatus("PUBLISHED");
                require(scenarioMapper.updateById(header) == 1, "缺料多候选情景发布状态更新失败");
            }
            audit(entity, command.getRequestId(), "PUBLISH", beforeStatus, "PUBLISHED",
                    "已重排" + candidates.size() + "道未执行工序并同步MES");
            return receipt(entity, mesReceipt.getAfterExecutionFingerprint());
        } catch (RuntimeException ex) {
            compensateMes(command.getRequestId() + ":MES-COMPENSATE", entity,
                    mesReceipt.getAfterExecutionFingerprint(), mesCompensation, "发布失败恢复MES原排程", ex);
            throw ex;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(PlanShortageLocalReplanDTO.CancelCommand command) {
        require(command != null && command.getCaseId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "取消参数不完整");
        caseMapper.lockRequest("CANCEL:" + command.getRequestId().trim());
        PlnShortageReplanCaseEntity duplicate = caseMapper.selectOne(new LambdaQueryWrapper<PlnShortageReplanCaseEntity>()
                .eq(PlnShortageReplanCaseEntity::getCancelRequestId, command.getRequestId().trim()));
        if (duplicate != null) {
            return true;
        }
        PlnShortageReplanCaseEntity entity = requireCase(command.getCaseId(), true);
        requireFactory(entity.getFactoryCode());
        require(Set.of("PROPOSED", "EXPIRED", "SUPERSEDED").contains(entity.getStatus()), "仅未发布方案可以取消");
        MomUser user = currentUser();
        if (entity.getScenarioId() != null) {
            PlnSchedulingScenarioEntity header = scenarioMapper.selectOne(
                    new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                            .eq(PlnSchedulingScenarioEntity::getId, entity.getScenarioId()).last("FOR UPDATE"));
            require(header != null && "EVALUATED".equals(header.getScenarioStatus()), "仅尚未选择的缺料情景可以整体取消");
            List<PlnShortageReplanCaseEntity> cases = caseMapper.selectList(
                    new LambdaQueryWrapper<PlnShortageReplanCaseEntity>()
                            .eq(PlnShortageReplanCaseEntity::getScenarioId, header.getId()).last("FOR UPDATE"));
            for (PlnShortageReplanCaseEntity item : cases) {
                String cancelRequestId = Objects.equals(item.getId(), entity.getId())
                        ? command.getRequestId().trim() : command.getRequestId().trim() + ':' + item.getId();
                item.setStatus("CANCELLED").setCancelRequestId(cancelRequestId)
                        .setCancelReason(command.getReason().trim()).setCancelledBy(userCode(user))
                        .setCancelledByName(userName(user)).setCancelledTime(LocalDateTime.now());
                require(caseMapper.updateById(item) == 1, "局部重排情景取消失败");
                audit(item, cancelRequestId, "CANCEL", "PROPOSED", "CANCELLED", command.getReason());
            }
            header.setScenarioStatus("CANCELLED");
            require(scenarioMapper.updateById(header) == 1, "局部重排情景取消状态保存失败");
            return true;
        }
        String before = entity.getStatus();
        entity.setStatus("CANCELLED").setCancelRequestId(command.getRequestId().trim())
                .setCancelReason(command.getReason().trim()).setCancelledBy(userCode(user))
                .setCancelledByName(userName(user)).setCancelledTime(LocalDateTime.now());
        require(caseMapper.updateById(entity) == 1, "局部重排方案取消失败");
        audit(entity, command.getRequestId(), "CANCEL", before, "CANCELLED", command.getReason());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanShortageLocalReplanDTO.PublishReceipt rollback(PlanShortageLocalReplanDTO.RollbackCommand command) {
        // 回退不是强制覆盖：只有发布后的 APS/MES 均未再次变化，才允许恢复原排程。
        require(command != null && command.getCaseId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()) && StrUtil.isNotBlank(command.getExpectedProposalFingerprint())
                && StrUtil.isNotBlank(command.getExpectedMesExecutionFingerprint()), "回退参数不完整");
        caseMapper.lockRequest("ROLLBACK:" + command.getRequestId().trim());
        PlnShortageReplanCaseEntity byRequest = caseMapper.selectOne(new LambdaQueryWrapper<PlnShortageReplanCaseEntity>()
                .eq(PlnShortageReplanCaseEntity::getRollbackRequestId, command.getRequestId().trim()));
        if (byRequest != null) {
            return receipt(byRequest, byRequest.getMesRollbackFingerprint());
        }
        PlnShortageReplanCaseEntity entity = requireCase(command.getCaseId(), true);
        requireFactory(entity.getFactoryCode());
        require("PUBLISHED".equals(entity.getStatus()), "仅已发布且未回退方案可以回退");
        require(Objects.equals(entity.getProposalFingerprint(), command.getExpectedProposalFingerprint()), "回退方案指纹不一致");
        require(Objects.equals(entity.getMesPublishedFingerprint(), command.getExpectedMesExecutionFingerprint()), "回退MES指纹不是发布后指纹");
        MomUser user = currentUser();
        require(!Objects.equals(entity.getPublisherCode(), userCode(user)), "发布人与回退复核人必须分离");
        PlnIssueEntity issue = lockIssue(entity.getIssueId());
        List<PlnShortageReplanLineEntity> lines = caseLines(entity.getId());
        List<PlnSchedulingOperationEntity> current = lockedOperations(issue);
        Map<String, PlnSchedulingOperationEntity> currentByCode = uniqueByProcess(current);
        List<PlnSchedulingOperationEntity> rollbackOperations = new ArrayList<>();
        for (PlnShortageReplanLineEntity line : lines) {
            if (!"MOVE".equals(line.getActionType())) {
                continue;
            }
            PlnSchedulingOperationEntity now = currentByCode.get(PlnFrozenRouteNodeIdentity.lineKey(line.getOriginalOperationId(), line.getSourceRouteNodeCode(), line.getProcessCode(), currentByCode));
            PlnSchedulingOperationEntity proposed = readOperation(line.getProposedOperationSnapshot());
            require(now != null && sameSchedule(now, proposed), "工序[" + line.getProcessCode() + "]发布后已再次变更，禁止覆盖回退");
            var restore = readOperation(line.getOriginalOperationSnapshot());
            require(Objects.equals(restore.getId(), line.getOriginalOperationId()), "回退快照与原工序主键不一致");
            PlnFrozenRouteNodeIdentity.sameIdentity(restore, now);
            PlnFrozenRouteNodeIdentity.sameIdentity(proposed, now);
            rollbackOperations.add(restore);
        }
        Map<String, PlnSchedulingOperationEntity> rollbackByCode = uniqueByProcess(rollbackOperations);
        operationSchedulingService.validateLocalReplan(issue, effectiveOperations(current, rollbackByCode),
                entity.getOriginalTaskId(), entity.getIssueId());
        MesApsLocalScheduleDTO.StateBundle mesBefore = mesStates(issue);
        PlnFrozenRouteNodeIdentity.correspondence(current, mesBefore);
        require(Objects.equals(entity.getMesPublishedFingerprint(), mesBefore.getExecutionFingerprint()), "MES执行或派工事实已变化，禁止回退");
        validateMesMovable(mesBefore, rollbackOperations.stream().map(PlnFrozenRouteNodeIdentity::key).collect(Collectors.toSet()));
        MesApsLocalScheduleDTO.ApplyReceipt mesReceipt = applyMes(command.getRequestId() + ":MES", entity,
                mesBefore.getExecutionFingerprint(), rollbackOperations);
        List<PlnSchedulingOperationEntity> mesCompensation = lines.stream()
                .filter(line -> "MOVE".equals(line.getActionType()))
                .map(line -> readOperation(line.getProposedOperationSnapshot())).toList();
        try {
            for (PlnSchedulingOperationEntity formal : current) {
                PlnSchedulingOperationEntity old = rollbackByCode.get(PlnFrozenRouteNodeIdentity.key(formal));
                if (old != null) {
                    copySchedule(old, formal);
                    require(schedulingOperationMapper.updateById(formal) == 1, "正式排程回退失败");
                }
            }
            updateFormalSummary(issue, current, "缺料局部重排已受控回退");
            entity.setStatus("ROLLED_BACK").setRollbackRequestId(command.getRequestId().trim())
                    .setRollbackReason(command.getReason().trim()).setRolledBackBy(userCode(user))
                    .setRolledBackByName(userName(user)).setRolledBackTime(LocalDateTime.now())
                    .setMesRollbackFingerprint(mesReceipt.getAfterExecutionFingerprint());
            require(caseMapper.updateById(entity) == 1, "局部重排回退状态更新失败");
            audit(entity, command.getRequestId(), "ROLLBACK", "PUBLISHED", "ROLLED_BACK", command.getReason());
            return receipt(entity, mesReceipt.getAfterExecutionFingerprint());
        } catch (RuntimeException ex) {
            compensateMes(command.getRequestId() + ":MES-COMPENSATE", entity,
                    mesReceipt.getAfterExecutionFingerprint(), mesCompensation, "回退失败恢复MES已发布排程", ex);
            throw ex;
        }
    }

    private void saveCandidateLines(PlnShortageReplanCaseEntity entity,
                                    List<PlnSchedulingOperationEntity> originals,
                                    Map<String, PlnSchedulingOperationEntity> candidateByCode,
                                    List<String> directCodes, Set<String> impactedCodes,
                                    MesApsLocalScheduleDTO.StateBundle mesBefore,
                                    LocalDateTime freezeUntil) {
        Map<String, MesApsLocalScheduleDTO.OperationState> mesByCode = PlnFrozenRouteNodeIdentity.correspondence(originals, mesBefore);
        for (PlnSchedulingOperationEntity original : originals) {
            boolean direct = directCodes.contains(PlnFrozenRouteNodeIdentity.key(original));
            boolean affected = impactedCodes.contains(PlnFrozenRouteNodeIdentity.key(original));
            PlnSchedulingOperationEntity candidate = candidateByCode.get(PlnFrozenRouteNodeIdentity.key(original));
            MesApsLocalScheduleDTO.OperationState mes = mesByCode.get(PlnFrozenRouteNodeIdentity.key(original));
            boolean frozen = original.getScheduledStartTime() == null || !original.getScheduledStartTime().isAfter(freezeUntil)
                    || mes != null && !Boolean.TRUE.equals(mes.getMovable());
            String action = affected ? "MOVE" : frozen ? "FROZEN" : "UNCHANGED";
            PlnShortageReplanLineEntity line = new PlnShortageReplanLineEntity()
                    .setCaseId(entity.getId()).setOriginalOperationId(original.getId())
                    .setProposalOperationId(affected ? candidate.getId() : null).setOperationSeq(original.getOperationSeq())
                    .setSourceRouteNodeCode(original.getSourceRouteNodeCode()).setProcessCode(original.getProcessCode()).setProcessName(original.getProcessName())
                    .setDirectAffected(direct).setDownstreamAffected(affected && !direct).setActionType(action)
                    .setBlockedReason("FROZEN".equals(action) ? frozenReason(original, mes, freezeUntil) : null)
                    .setOriginalStartTime(original.getScheduledStartTime()).setOriginalEndTime(original.getScheduledEndTime())
                    .setProposedStartTime(affected ? candidate.getScheduledStartTime() : original.getScheduledStartTime())
                    .setProposedEndTime(affected ? candidate.getScheduledEndTime() : original.getScheduledEndTime())
                    .setOriginalResourceCode(original.getResourceCode()).setProposedResourceCode(affected ? candidate.getResourceCode() : original.getResourceCode())
                    .setOriginalDeviceCode(original.getDeviceCode()).setProposedDeviceCode(affected ? candidate.getDeviceCode() : original.getDeviceCode())
                    .setOriginalEmployeeCode(original.getEmployeeCode()).setProposedEmployeeCode(affected ? candidate.getEmployeeCode() : original.getEmployeeCode())
                    .setOriginalToolingCodes(original.getToolingCodes()).setProposedToolingCodes(affected ? candidate.getToolingCodes() : original.getToolingCodes())
                    .setShiftMinutes(affected ? Math.max(0, Duration.between(original.getScheduledStartTime(), candidate.getScheduledStartTime()).toMinutes()) : 0)
                    .setOriginalOperationSnapshot(json(original)).setProposedOperationSnapshot(affected ? json(candidate) : null)
                    .setFactoryCode(entity.getFactoryCode());
            require(lineMapper.insert(line) == 1, "局部重排工序差异保存失败");
        }
    }

    private void rankCandidates(List<ShortageCandidateBuild> builds) {
        List<ShortageCandidateBuild> ordered = builds.stream().sorted(Comparator
                .comparing((ShortageCandidateBuild item) -> !Boolean.TRUE.equals(item.task().getCandidateFeasible()))
                .thenComparing((ShortageCandidateBuild item) -> item.task().getCandidateScore(), Comparator.reverseOrder())
                .thenComparingLong(ShortageCandidateBuild::completionDelay)
                .thenComparingLong(ShortageCandidateBuild::totalShift)
                .thenComparingInt(ShortageCandidateBuild::resourceChanges)
                .thenComparingLong(ShortageCandidateBuild::changeoverMinutes)
                .thenComparing(item -> item.task().getCandidateCode())).toList();
        for (int index = 0; index < ordered.size(); index++) {
            PlnSchedulingTaskEntity task = ordered.get(index).task();
            task.setCandidateRank(index + 1);
            require(taskMapper.updateById(task) == 1, "局部重排候选排名保存失败");
        }
    }

    private BigDecimal candidateBaseScore(long completionDelay, long totalShift,
                                          int resourceChanges, long changeoverMinutes) {
        BigDecimal penalty = BigDecimal.valueOf(Math.min(45, completionDelay / 60.0 * 4)
                + Math.min(25, totalShift / 60.0)
                + Math.min(15, resourceChanges * 3.0)
                + Math.min(15, changeoverMinutes / 30.0));
        return BigDecimal.valueOf(100).subtract(penalty).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private long totalShift(List<PlnSchedulingOperationEntity> originals,
                            Map<String, PlnSchedulingOperationEntity> candidates, Set<String> affected) {
        return originals.stream().filter(item -> affected.contains(PlnFrozenRouteNodeIdentity.key(item)))
                .mapToLong(item -> Math.max(0, Duration.between(item.getScheduledStartTime(),
                        candidates.get(PlnFrozenRouteNodeIdentity.key(item)).getScheduledStartTime()).toMinutes())).sum();
    }

    private int resourceChanges(List<PlnSchedulingOperationEntity> originals,
                                Map<String, PlnSchedulingOperationEntity> candidates, Set<String> affected) {
        return (int) originals.stream().filter(item -> affected.contains(PlnFrozenRouteNodeIdentity.key(item)))
                .filter(item -> {
                    PlnSchedulingOperationEntity candidate = candidates.get(PlnFrozenRouteNodeIdentity.key(item));
                    return !Objects.equals(item.getResourceCode(), candidate.getResourceCode())
                            || !Objects.equals(item.getDeviceCode(), candidate.getDeviceCode())
                            || !Objects.equals(item.getEmployeeCode(), candidate.getEmployeeCode())
                            || !Objects.equals(item.getToolingCodes(), candidate.getToolingCodes());
                }).count();
    }

    private void enrichCandidate(PlnShortageReplanCaseEntity entity) {
        if (entity == null || entity.getScenarioId() == null || entity.getProposalTaskId() == null) {
            return;
        }
        PlnSchedulingTaskEntity task = taskMapper.selectById(entity.getProposalTaskId());
        PlnSchedulingScenarioEntity header = scenarioMapper.selectById(entity.getScenarioId());
        if (task != null) {
            entity.setCandidateCode(task.getCandidateCode());
            entity.setCandidateName(task.getCandidateName());
            entity.setCandidateScore(task.getCandidateScore());
            entity.setCandidateRank(task.getCandidateRank());
            entity.setCandidateFeasible(task.getCandidateFeasible());
            entity.setSelectedCandidate(task.getSelectedCandidate());
            entity.setCandidateMetricsSnapshot(task.getCandidateMetricsSnapshot());
        }
        if (header != null) {
            entity.setScenarioNo(header.getScenarioNo());
        }
    }

    private void saveProposalDetail(PlnSchedulingTaskEntity task, PlnIssueEntity issue,
                                    List<PlnSchedulingOperationEntity> operations) {
        PlnSchedulingTaskDetailEntity detail = new PlnSchedulingTaskDetailEntity();
        BeanUtils.copyProperties(issue, detail, "id", "createTime", "updateTime", "createBy", "updateBy");
        detail.setId(null);
        detail.setTaskId(task.getId());
        detail.setIssueId(issue.getId());
        detail.setLockStatus(false);
        detail.setLockTaskId(issue.getLockTaskId());
        detail.setStatus("PROPOSED");
        detail.setOperationCount(operations.size());
        detail.setOperationStartTime(operations.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                .min(LocalDateTime::compareTo).orElse(null));
        detail.setOperationEndTime(operations.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                .max(LocalDateTime::compareTo).orElse(null));
        require(taskDetailMapper.insert(detail) == 1, "候选排程任务明细保存失败");
    }

    private void updateFormalSummary(PlnIssueEntity issue, List<PlnSchedulingOperationEntity> operations,
                                     String operationScheduleSummary) {
        LocalDateTime start = operations.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElseThrow();
        LocalDateTime end = operations.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElseThrow();
        issue.setScheduledDate(start.toLocalDate());
        issue.setScheduledEndDate(end.toLocalDate());
        require(issueMapper.updateById(issue) == 1, "生产计划排程日期更新失败");
        PlnSchedulingTaskDetailEntity detail = taskDetailMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                        .eq(PlnSchedulingTaskDetailEntity::getTaskId, issue.getLockTaskId())
                        .eq(PlnSchedulingTaskDetailEntity::getIssueId, issue.getId()).last("LIMIT 1"));
        if (detail != null) {
            detail.setScheduledDate(start.toLocalDate());
            detail.setScheduledEndDate(end.toLocalDate());
            detail.setOperationStartTime(start);
            detail.setOperationEndTime(end);
            detail.setOperationCount(operations.size());
            detail.setOperationScheduleStatus("SCHEDULED");
            detail.setOperationHardBlocked(false);
            detail.setOperationScheduleSummary(operationScheduleSummary);
            require(taskDetailMapper.updateById(detail) == 1, "正式排程任务明细更新失败");
        }
    }

    /**
     * 将工序排程差异发送给 MES，并校验 MES 返回的同步回执。
     */
    private MesApsLocalScheduleDTO.ApplyReceipt applyMes(String requestId, PlnShortageReplanCaseEntity entity,
                                                         String expectedFingerprint,
                                                         List<PlnSchedulingOperationEntity> operations) {
        MesApsLocalScheduleDTO.ApplyCommand command = new MesApsLocalScheduleDTO.ApplyCommand();
        command.setRequestId(requestId).setPlanNo(entity.getIssueNo()).setFactoryCode(entity.getFactoryCode())
                .setExpectedExecutionFingerprint(expectedFingerprint).setReplanCaseId(entity.getId())
                .setOperations(operations.stream().map(this::toMesUpdate).toList());
        R<MesApsLocalScheduleDTO.ApplyReceipt> response = remoteMesTaskService.applyApsLocalSchedule(command);
        require(response != null && response.isOk() && response.getData() != null, "MES未确认局部重排同步成功");
        return response.getData();
    }

    private MesApsLocalScheduleDTO.OperationUpdate toMesUpdate(PlnSchedulingOperationEntity item) {
        MesApsLocalScheduleDTO.OperationUpdate result = new MesApsLocalScheduleDTO.OperationUpdate();
        result.setSourceRouteNodeCode(item.getSourceRouteNodeCode()).setRouteCode(item.getProcessCode()).setPlannedStartTime(item.getScheduledStartTime())
                .setPlannedEndTime(item.getScheduledEndTime()).setResourceCode(item.getResourceCode())
                .setResourceName(item.getResourceName()).setResourceSlotNo(item.getResourceSlotNo())
                .setResourceGroupCode(item.getResourceGroupCode()).setResourceGroupName(item.getResourceGroupName())
                .setResourceGroupCapacity(item.getResourceGroupCapacity()).setResourceGroupSlotNo(item.getResourceGroupSlotNo())
                .setDeviceCode(item.getDeviceCode()).setDeviceName(item.getDeviceName())
                .setEmployeeCode(item.getEmployeeCode()).setEmployeeName(item.getEmployeeName())
                .setToolingCodes(item.getToolingCodes()).setToolingNames(item.getToolingNames())
                .setToolingAllocationSnapshot(item.getToolingAllocationSnapshot())
                .setToolingLifeUsageSnapshot(item.getToolingLifeUsageSnapshot()).setApsOperationSnapshot(json(item));
        return result;
    }

    private void copySchedule(PlnSchedulingOperationEntity source, PlnSchedulingOperationEntity target) {
        PlnFrozenRouteNodeIdentity.sameIdentity(source, target);
        target.setResourceType(source.getResourceType());
        target.setResourceCode(source.getResourceCode());
        target.setResourceName(source.getResourceName());
        target.setResourceSlotNo(source.getResourceSlotNo());
        target.setResourceGroupCode(source.getResourceGroupCode());
        target.setResourceGroupName(source.getResourceGroupName());
        target.setResourceGroupCapacity(source.getResourceGroupCapacity());
        target.setResourceGroupSlotNo(source.getResourceGroupSlotNo());
        target.setCandidateResourceSnapshot(source.getCandidateResourceSnapshot());
        target.setDeviceCode(source.getDeviceCode());
        target.setDeviceName(source.getDeviceName());
        target.setDeviceAvailabilitySnapshot(source.getDeviceAvailabilitySnapshot());
        target.setEmployeeCode(source.getEmployeeCode());
        target.setEmployeeName(source.getEmployeeName());
        target.setWorkforceAvailabilitySnapshot(source.getWorkforceAvailabilitySnapshot());
        target.setToolingCodes(source.getToolingCodes());
        target.setToolingNames(source.getToolingNames());
        target.setToolingAllocationSnapshot(source.getToolingAllocationSnapshot());
        target.setToolingLifeUsageSnapshot(source.getToolingLifeUsageSnapshot());
        target.setMaterialAvailableTime(source.getMaterialAvailableTime());
        target.setMaterialConstraintStatus(source.getMaterialConstraintStatus());
        target.setMaterialConstraintSummary(source.getMaterialConstraintSummary());
        target.setMaterialConstraintSnapshot(source.getMaterialConstraintSnapshot());
        target.setMaterialConstraintFingerprint(source.getMaterialConstraintFingerprint());
        target.setMaterialLineageFingerprint(source.getMaterialLineageFingerprint());
        target.setScheduledStartTime(source.getScheduledStartTime());
        target.setScheduledEndTime(source.getScheduledEndTime());
        target.setTransferReadyTime(source.getTransferReadyTime());
        target.setScheduleStatus(source.getScheduleStatus());
        target.setHardBlocked(source.getHardBlocked());
        target.setConstraintSummary(source.getConstraintSummary());
        target.setSourceSnapshot(source.getSourceSnapshot());
    }

    private PlnSchedulingOperationEntity detachedOperation(PlnSchedulingOperationEntity source) {
        PlnSchedulingOperationEntity result = new PlnSchedulingOperationEntity();
        BeanUtils.copyProperties(source, result);
        return result;
    }

    /**
     * 本地 APS 事务失败后的 MES 补偿操作。
     *
     * <p>补偿仍失败时明确提示需要人工对账，避免将外部系统不一致误报为普通事务回滚。</p>
     */
    private void compensateMes(String requestId, PlnShortageReplanCaseEntity entity, String expectedFingerprint,
                               List<PlnSchedulingOperationEntity> operations, String action,
                               RuntimeException originalFailure) {
        try {
            applyMes(requestId, entity, expectedFingerprint, operations);
        } catch (RuntimeException compensationFailure) {
            throw new CheckedException(action + "失败，PLAN事务将回滚但MES可能需要人工对账；原错误："
                    + originalFailure.getMessage() + "；补偿错误：" + compensationFailure.getMessage());
        }
    }

    private List<PlnSchedulingOperationEntity> effectiveOperations(List<PlnSchedulingOperationEntity> formal,
                                                                   Map<String, PlnSchedulingOperationEntity> replacements) {
        List<PlnSchedulingOperationEntity> result = new ArrayList<>();
        for (PlnSchedulingOperationEntity current : formal) {
            PlnSchedulingOperationEntity copy = new PlnSchedulingOperationEntity();
            BeanUtils.copyProperties(current, copy);
            PlnSchedulingOperationEntity replacement = replacements.get(PlnFrozenRouteNodeIdentity.key(current));
            if (replacement != null) {
                copySchedule(replacement, copy);
            }
            result.add(copy);
        }
        return result;
    }

    private List<PlnSchedulingOperationEntity> proposalOperations(PlnShortageReplanCaseEntity entity,
                                                                  List<PlnShortageReplanLineEntity> lines) {
        Set<Long> ids = lines.stream().filter(line -> "MOVE".equals(line.getActionType()))
                .map(PlnShortageReplanLineEntity::getProposalOperationId).filter(Objects::nonNull).collect(Collectors.toSet());
        require(!ids.isEmpty(), "方案没有可发布工序");
        List<PlnSchedulingOperationEntity> rows = schedulingOperationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>().in(PlnSchedulingOperationEntity::getId, ids)
                        .eq(PlnSchedulingOperationEntity::getTaskId, entity.getProposalTaskId())
                        .eq(PlnSchedulingOperationEntity::getIssueId, entity.getIssueId())
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq));
        require(rows.size() == ids.size(), "候选排程工序已缺失");
        Map<Long, PlnShortageReplanLineEntity> byId = lines.stream().filter(line -> line.getProposalOperationId() != null)
                .collect(Collectors.toMap(PlnShortageReplanLineEntity::getProposalOperationId, Function.identity()));
        for (PlnSchedulingOperationEntity row : rows) {
            PlnSchedulingOperationEntity frozen = readOperation(byId.get(row.getId()).getProposedOperationSnapshot());
            var line = byId.get(row.getId());
            var original = readOperation(line.getOriginalOperationSnapshot());
            require(Objects.equals(original.getId(), line.getOriginalOperationId()), "重排明细与原工序主键不一致");
            PlnFrozenRouteNodeIdentity.lineKey(line.getOriginalOperationId(), line.getSourceRouteNodeCode(), line.getProcessCode(), uniqueByProcess(List.of(original)));
            PlnFrozenRouteNodeIdentity.sameIdentity(row, original);
            PlnFrozenRouteNodeIdentity.sameIdentity(row, frozen);
            require(Objects.equals(operationFingerprint(row), operationFingerprint(frozen)),
                    "候选工序[" + row.getProcessCode() + "]已被修改");
        }
        return rows;
    }

    private List<PlnSchedulingOperationEntity> lockedOperations(PlnIssueEntity issue) {
        require(Boolean.TRUE.equals(issue.getLockStatus()) && issue.getLockTaskId() != null, "生产计划尚未形成正式锁定排程");
        require(!Set.of("3", "4").contains(issue.getStatus()), "生产计划已完成或关闭，禁止局部重排");
        List<PlnSchedulingOperationEntity> rows = schedulingOperationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getTaskId, issue.getLockTaskId())
                        .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                        .eq(PlnSchedulingOperationEntity::getLockStatus, true)
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq).last("FOR UPDATE"));
        require(!rows.isEmpty() && rows.stream().noneMatch(item -> Boolean.TRUE.equals(item.getHardBlocked())),
                "正式APS工序排程不存在或存在硬阻断");
        return rows;
    }

    private void validateScope(PlanMatMrpEntity mrp, PlnIssueEntity issue, String factoryCode) {
        require(Objects.equals(mrp.getFactoryCode(), factoryCode) && Objects.equals(issue.getFactoryCode(), factoryCode), "MRP或计划超出当前工厂范围");
        boolean samePlanningSource = issue.getMpsLineId() != null
                ? Objects.equals(issue.getMpsLineId(), mrp.getMpsLineId())
                : StrUtil.isNotBlank(issue.getPlanningSourceKey())
                ? Objects.equals(issue.getPlanningSourceKey(), mrp.getPlanningSourceKey())
                : Objects.equals(mrp.getPlanDemandNo(),
                StrUtil.blankToDefault(issue.getSourceDemandNo(), issue.getIssueNo()));
        require(samePlanningSource, "MRP与生产计划不属于同一需求或MPS拆量桶");
        require(optional(mrp.getRemainingDemandNum()).compareTo(BigDecimal.ZERO) > 0
                        && Set.of("SHORTAGE", "SUBSTITUTE_PENDING", "PARTIAL_SHORTAGE").contains(mrp.getReadinessStatus()),
                "MRP当前已不再缺料，禁止生成或发布延期重排");
    }

    private MesApsLocalScheduleDTO.StateBundle mesStates(PlnIssueEntity issue) {
        R<MesApsLocalScheduleDTO.StateBundle> response = remoteMesTaskService.apsOperationStates(issue.getIssueNo(), issue.getFactoryCode());
        require(response != null && response.isOk() && response.getData() != null
                && StrUtil.isNotBlank(response.getData().getExecutionFingerprint()), "MES执行事实不可用，禁止局部重排");
        return response.getData();
    }

    private void validateMesMovable(MesApsLocalScheduleDTO.StateBundle bundle, Set<String> processCodes) {
        if (!Boolean.TRUE.equals(bundle.getMesTaskPresent())) {
            return;
        }
        Map<String, MesApsLocalScheduleDTO.OperationState> byCode = PlnFrozenRouteNodeIdentity.states(bundle);
        for (String code : processCodes) {
            MesApsLocalScheduleDTO.OperationState state = PlnFrozenRouteNodeIdentity.state(byCode, code);
            require(state != null, "MES工艺快照缺少工序[" + code + "]");
            require(Boolean.TRUE.equals(state.getMovable()), "MES工序[" + code + "]不可移动：" + state.getBlockedReason());
        }
    }

    private Set<String> downstreamClosure(List<PlnSchedulingOperationEntity> operations, List<String> direct) {
        return PlnFrozenRouteNodeIdentity.closure(operations, direct);
    }

    private List<String> resolveDirectAffected(PlanMatMrpEntity mrp,
                                               List<PlnSchedulingOperationEntity> operations) {
        MdmRouteMaterialAssignmentQueryDTO query = new MdmRouteMaterialAssignmentQueryDTO();
        query.setFactoryCode(mrp.getFactoryCode());
        query.setRouteCodes(operations.stream().map(PlnSchedulingOperationEntity::getRouteCode)
                .filter(StrUtil::isNotBlank).collect(Collectors.toSet()));
        query.setMaterialCodes(Set.of(mrp.getMatCode()));
        R<List<MdmProcessProcedureMatEntity>> response = remoteProcessRouteService.getMaterialAssignments(query);
        require(response != null && response.isOk() && response.getData() != null,
                "MDM工序投料映射服务不可用，禁止猜测受影响工序");
        Map<String, PlnSchedulingOperationEntity> frozen = uniqueByProcess(operations);
        Set<String> direct = new LinkedHashSet<>();
        for (var assignment : response.getData()) {
            if (!Objects.equals(mrp.getMatCode(), assignment.getMaterialCode())) continue;
            for (var operation : operations) {
                if (!Objects.equals(assignment.getRouteCode(), operation.getRouteCode())
                        || !Objects.equals(assignment.getProcessCode(), operation.getProcessCode())) continue;
                if (StrUtil.isNotBlank(operation.getSourceRouteNodeCode())) {
                    if (Objects.equals(operation.getSourceRouteNodeCode(), assignment.getRouteNodeCode())) direct.add(PlnFrozenRouteNodeIdentity.key(operation));
                } else {
                    // A current material relation does not confer a node identity on a historical operation.
                    require(operations.stream().filter(row -> Objects.equals(row.getProcessCode(), operation.getProcessCode())).count() == 1
                            && response.getData().stream().filter(row -> Objects.equals(row.getRouteCode(), operation.getRouteCode())
                            && Objects.equals(row.getProcessCode(), operation.getProcessCode())).map(MdmProcessProcedureMatEntity::getRouteNodeCode)
                            .distinct().count() == 1, "旧投料工序无法唯一桥接，禁止猜测原冻结身份");
                    direct.add(PlnFrozenRouteNodeIdentity.key(operation));
                }
            }
        }
        require(frozen.keySet().containsAll(direct), "投料影响范围不属于原冻结工序");
        return direct.stream().sorted().toList();
    }

    private Map<String, PlnSchedulingOperationEntity> uniqueByProcess(List<PlnSchedulingOperationEntity> rows) {
        return PlnFrozenRouteNodeIdentity.index(rows);
    }

    private long maximumShift(List<PlnSchedulingOperationEntity> originals,
                              Map<String, PlnSchedulingOperationEntity> candidates, Set<String> affected) {
        return originals.stream().filter(item -> affected.contains(PlnFrozenRouteNodeIdentity.key(item)))
                .mapToLong(item -> Math.max(0, Duration.between(item.getScheduledStartTime(),
                        candidates.get(PlnFrozenRouteNodeIdentity.key(item)).getScheduledStartTime()).toMinutes())).max().orElse(0);
    }

    private String deliveryRisk(PlnIssueEntity issue, LocalDateTime completion) {
        if (issue.getDemandTime() == null) {
            return "AT_RISK";
        }
        LocalDateTime deadline = issue.getDemandTime().atTime(LocalTime.MAX);
        if (completion.isAfter(deadline)) {
            return "LATE";
        }
        return Duration.between(completion, deadline).toHours() < 24 ? "AT_RISK" : "ON_TIME";
    }

    private String frozenReason(PlnSchedulingOperationEntity operation,
                                MesApsLocalScheduleDTO.OperationState mes, LocalDateTime freezeUntil) {
        if (mes != null && !Boolean.TRUE.equals(mes.getMovable())) {
            return mes.getBlockedReason();
        }
        return "计划开始时间不晚于冻结边界" + freezeUntil;
    }

    private String mrpFingerprint(PlanMatMrpEntity item) {
        return hash(String.join("|", Objects.toString(item.getId(), ""), Objects.toString(item.getPlanDemandNo(), ""),
                Objects.toString(item.getMatCode(), ""), Objects.toString(item.getRemainingDemandNum(), ""),
                Objects.toString(item.getReadinessStatus(), ""), Objects.toString(item.getSubstituteStatus(), ""),
                Objects.toString(item.getMaterialReservationNo(), ""), Objects.toString(item.getMaterialReservationStatus(), ""),
                Objects.toString(item.getMainMaterialReservationNo(), ""), Objects.toString(item.getMainMaterialReservationStatus(), ""),
                Objects.toString(item.getBomPathFingerprint(), ""), Objects.toString(item.getMaxBomLevel(), ""),
                Objects.toString(item.getMrpRunNo(), ""), Objects.toString(item.getInventoryAllocationPriority(), ""),
                Objects.toString(item.getInventoryAllocationSequence(), ""), Objects.toString(item.getInventoryPeggedNum(), ""),
                Objects.toString(item.getInventoryPeggingStatus(), ""), Objects.toString(item.getInventoryPeggingFingerprint(), "")));
    }

    /**
     * 读取分钟级供应证据并生成指纹。只有齐套分析明确标记证据完整时才允许生成候选。
     */
    private SupplyEvidence supplyEvidence(PlanMatMrpEntity mrp, LocalDateTime requestedSupplyTime) {
        PlanMrpShortageQueryDTO query = new PlanMrpShortageQueryDTO();
        query.setFactoryCode(mrp.getFactoryCode());
        query.setPlanDemandNo(mrp.getPlanDemandNo());
        query.setSupplyEvidenceVersion(9);
        PlanMrpShortageAnalysisDTO analysis = planMatMrpService.shortageAnalysis(query);
        PlanMrpShortageLineDTO line = Optional.ofNullable(analysis).map(PlanMrpShortageAnalysisDTO::getLines)
                .orElse(List.of()).stream().filter(item -> Objects.equals(item.getMrpId(), mrp.getId()))
                .findFirst().orElseThrow(() -> new CheckedException("当前齐套分析不包含目标MRP记录"));
        require(Boolean.TRUE.equals(line.getSupplyEvidenceComplete()) && line.getExpectedSupplyTime() != null,
                "缺料仍缺少可核验的分钟级正式供应或供应商承诺时刻，禁止生成重排方案");
        require(!requestedSupplyTime.isBefore(line.getExpectedSupplyTime()),
                "输入的预计齐料时刻早于权威供应证据时刻" + line.getExpectedSupplyTime());
        String supplyRaw = String.join("|", Objects.toString(line.getExpectedSupplyDate(), ""),
                Objects.toString(line.getExpectedSupplyTime(), ""), Objects.toString(line.getSupplyTimePrecision(), ""),
                Objects.toString(line.getSupplyDateBasis(), ""), Objects.toString(line.getSupplyType(), ""),
                Objects.toString(line.getSupplyPlanStatus(), ""), Objects.toString(line.getSupplyPlanNos(), ""),
                Objects.toString(line.getPlannedSupplyNum(), ""), Objects.toString(line.getOrderedSupplyNum(), ""),
                Objects.toString(line.getArrivedSupplyNum(), ""), Objects.toString(line.getOpenOrderedSupplyNum(), ""),
                Objects.toString(line.getConfirmedSupplyNum(), ""), Objects.toString(line.getSupplierCommitmentStatus(), ""),
                Objects.toString(line.getSupplierCommitmentNos(), ""));
        return new SupplyEvidence(hash(mrpFingerprint(mrp) + '|' + supplyRaw));
    }

    private String scheduleFingerprint(List<PlnSchedulingOperationEntity> rows) {
        String raw = rows.stream().sorted(Comparator.comparing(PlnSchedulingOperationEntity::getOperationSeq,
                        Comparator.nullsLast(Integer::compareTo)).thenComparing(PlnSchedulingOperationEntity::getId,
                        Comparator.nullsLast(Long::compareTo)))
                .map(this::operationFingerprint).collect(Collectors.joining("||"));
        return hash(raw);
    }

    private String operationFingerprint(PlnSchedulingOperationEntity item) {
        return String.join("|", Objects.toString(item.getId(), ""), Objects.toString(item.getTaskId(), ""),
                Objects.toString(item.getIssueId(), ""), Objects.toString(item.getOperationSeq(), ""),
                Objects.toString(item.getRouteCode(), ""), Objects.toString(item.getProcessCode(), ""),
                Objects.toString(item.getPredecessorCodes(), ""), Objects.toString(item.getResourceType(), ""),
                Objects.toString(item.getResourceCode(), ""), Objects.toString(item.getResourceSlotNo(), ""),
                Objects.toString(item.getResourceGroupCode(), ""), Objects.toString(item.getResourceGroupCapacity(), ""),
                Objects.toString(item.getResourceGroupSlotNo(), ""), Objects.toString(item.getDeviceCode(), ""),
                Objects.toString(item.getEmployeeCode(), ""), Objects.toString(item.getToolingCodes(), ""),
                Objects.toString(item.getCandidateResourceSnapshot(), ""),
                Objects.toString(item.getDeviceAvailabilitySnapshot(), ""),
                Objects.toString(item.getWorkforceAvailabilitySnapshot(), ""),
                Objects.toString(item.getToolingAllocationSnapshot(), ""),
                Objects.toString(item.getToolingLifeUsageSnapshot(), ""),
                Objects.toString(item.getMaterialAvailableTime(), ""),
                Objects.toString(item.getMaterialConstraintStatus(), ""),
                Objects.toString(item.getMaterialConstraintFingerprint(), ""),
                Objects.toString(item.getMaterialLineageFingerprint(), ""),
                Objects.toString(item.getScheduledStartTime(), ""), Objects.toString(item.getScheduledEndTime(), ""),
                Objects.toString(item.getTransferReadyTime(), ""), Objects.toString(item.getScheduleStatus(), ""),
                Objects.toString(item.getHardBlocked(), ""), Objects.toString(item.getLockStatus(), ""));
    }

    private boolean sameSchedule(PlnSchedulingOperationEntity left, PlnSchedulingOperationEntity right) {
        return Objects.equals(left.getScheduledStartTime(), right.getScheduledStartTime())
                && Objects.equals(left.getScheduledEndTime(), right.getScheduledEndTime())
                && Objects.equals(left.getResourceCode(), right.getResourceCode())
                && Objects.equals(left.getResourceSlotNo(), right.getResourceSlotNo())
                && Objects.equals(left.getDeviceCode(), right.getDeviceCode())
                && Objects.equals(left.getEmployeeCode(), right.getEmployeeCode())
                && Objects.equals(left.getToolingCodes(), right.getToolingCodes());
    }

    private PlnSchedulingOperationEntity readOperation(String json) {
        try {
            return objectMapper.readValue(json, PlnSchedulingOperationEntity.class);
        } catch (Exception ex) {
            throw new CheckedException("局部重排工序快照损坏");
        }
    }

    private PlanMatMrpEntity lockMrp(Long id) {
        PlanMatMrpEntity result = mrpMapper.selectOne(new LambdaQueryWrapper<PlanMatMrpEntity>()
                .eq(PlanMatMrpEntity::getId, id).last("FOR UPDATE"));
        require(result != null, "MRP记录不存在");
        return result;
    }

    private void ensureNoInsertionProposal(Long issueId) {
        LocalDateTime now = LocalDateTime.now();
        long insertion = insertionCaseMapper.selectCount(new LambdaQueryWrapper<PlnInsertionReplanCaseEntity>()
                .eq(PlnInsertionReplanCaseEntity::getInsertionIssueId, issueId)
                .in(PlnInsertionReplanCaseEntity::getStatus, "PROPOSED", "SELECTED")
                .gt(PlnInsertionReplanCaseEntity::getExpiresTime, now));
        long displaced = insertionIssueMapper.selectCount(new LambdaQueryWrapper<PlnInsertionReplanIssueEntity>()
                .eq(PlnInsertionReplanIssueEntity::getIssueId, issueId)
                .inSql(PlnInsertionReplanIssueEntity::getCaseId,
                        "SELECT id FROM pln_insertion_replan_case WHERE del_flag='0' AND status IN ('PROPOSED','SELECTED')"
                                + " AND expires_time>CURRENT_TIMESTAMP"));
        require(insertion + displaced == 0, "该生产计划已有待发布紧急插单情景，请先完成、取消或等待其过期");
    }

    private PlnIssueEntity lockIssue(Long id) {
        PlnIssueEntity result = issueMapper.selectOne(new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getId, id).last("FOR UPDATE"));
        require(result != null, "生产计划不存在");
        return result;
    }

    private PlnIssueEntity lockIssueForMrp(PlanMatMrpEntity mrp) {
        LambdaQueryWrapper<PlnIssueEntity> query = new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getFactoryCode, mrp.getFactoryCode())
                .isNull(PlnIssueEntity::getSourceMrpId)
                .eq(PlnIssueEntity::getLockStatus, true)
                .notIn(PlnIssueEntity::getStatus, "3", "4")
                .orderByDesc(PlnIssueEntity::getLockTime).orderByDesc(PlnIssueEntity::getId);
        if (mrp.getMpsLineId() != null) {
            query.eq(PlnIssueEntity::getMpsLineId, mrp.getMpsLineId());
        } else {
            query.and(source -> source.eq(PlnIssueEntity::getSourceDemandNo, mrp.getPlanDemandNo())
                    .or().and(legacy -> legacy.isNull(PlnIssueEntity::getSourceDemandNo)
                            .eq(PlnIssueEntity::getIssueNo, mrp.getPlanDemandNo())));
        }
        PlnIssueEntity result = issueMapper.selectOne(query.last("LIMIT 1 FOR UPDATE"));
        require(result != null, "该MRP需求尚未关联可重排的正式锁定生产计划");
        return result;
    }

    private PlnShortageReplanCaseEntity requireCase(Long id, boolean lock) {
        LambdaQueryWrapper<PlnShortageReplanCaseEntity> query = new LambdaQueryWrapper<PlnShortageReplanCaseEntity>()
                .eq(PlnShortageReplanCaseEntity::getId, id);
        if (lock) {
            query.last("FOR UPDATE");
        }
        PlnShortageReplanCaseEntity result = caseMapper.selectOne(query);
        require(result != null, "局部重排方案不存在");
        return result;
    }

    private List<PlnShortageReplanLineEntity> caseLines(Long caseId) {
        return lineMapper.selectList(new LambdaQueryWrapper<PlnShortageReplanLineEntity>()
                .eq(PlnShortageReplanLineEntity::getCaseId, caseId)
                .orderByAsc(PlnShortageReplanLineEntity::getOperationSeq));
    }

    private void audit(PlnShortageReplanCaseEntity entity, String requestId, String type,
                       String before, String after, String content) {
        MomUser user = currentUser();
        require(auditMapper.insert(new PlnShortageReplanOperationEntity().setCaseId(entity.getId())
                .setRequestId(requestId.trim()).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                .setContent(content).setOperatorCode(userCode(user)).setOperatorName(userName(user))
                .setOperationTime(LocalDateTime.now()).setFactoryCode(entity.getFactoryCode())) == 1, "局部重排审计履历保存失败");
    }

    private PlanShortageLocalReplanDTO.PublishReceipt receipt(PlnShortageReplanCaseEntity entity, String mesFingerprint) {
        return new PlanShortageLocalReplanDTO.PublishReceipt().setCaseId(entity.getId()).setCaseNo(entity.getCaseNo())
                .setIssueNo(entity.getIssueNo()).setStatus(entity.getStatus()).setMovedOperationCount(entity.getMovedOperationCount())
                .setNewCompletionTime("ROLLED_BACK".equals(entity.getStatus()) ? entity.getOriginalCompletionTime() : entity.getProposedCompletionTime())
                .setMesAfterExecutionFingerprint(mesFingerprint);
    }

    private int bounded(Integer value, int defaultValue, int min, int max, String message) {
        int result = value == null ? defaultValue : value;
        require(result >= min && result <= max, message);
        return result;
    }

    private List<String> normalizeCodes(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(StrUtil::isNotBlank).map(String::trim).distinct().sorted().toList();
    }

    private List<String> splitCodes(String value) {
        return StrUtil.isBlank(value) ? List.of() : Arrays.stream(value.split(","))
                .map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
    }

    private void requireFactory(String factoryCode) {
        String current = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(current) || Objects.equals(current, factoryCode), "请求工厂超出当前数据范围");
    }

    private BigDecimal optional(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private MomUser currentUser() {
        try {
            return SecurityUtils.getUser();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String userCode(MomUser user) {
        return user == null ? "SYSTEM" : user.getUsername();
    }

    private String userName(MomUser user) {
        return user == null ? "系统" : StrUtil.blankToDefault(user.getName(), user.getUsername());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("局部重排证据序列化失败");
        }
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new CheckedException("局部重排证据指纹生成失败");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    /**
     * 经过完整性校验后的供应证据摘要。
     */
    private record SupplyEvidence(String fingerprint) {
    }

    /**
     * 一个候选方案的求解目标和排序策略。
     */
    private record ShortageCandidateProfile(String code, String name, String solverStrategy,
                                            String sequenceStrategy) {
    }

    /**
     * 候选排名所需的持久化对象和核心指标。
     */
    private record ShortageCandidateBuild(PlnShortageReplanCaseEntity entity,
                                          PlnSchedulingTaskEntity task,
                                          long completionDelay, long totalShift,
                                          int resourceChanges, long changeoverMinutes) {
    }
}
