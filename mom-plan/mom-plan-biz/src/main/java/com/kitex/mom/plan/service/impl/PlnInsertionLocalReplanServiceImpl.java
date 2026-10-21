package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.entity.SearchPage;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesApsLocalScheduleDTO;
import com.kitex.mom.plan.constant.IssueStatusEnum;
import com.kitex.mom.plan.dto.PlanInsertionLocalReplanDTO;
import com.kitex.mom.plan.dto.PlnMaterialConstraintEvaluationDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlnInsertionLocalReplanService;
import com.kitex.mom.plan.service.PlnMaterialConstraintService;
import com.kitex.mom.plan.service.PlnOperationSchedulingService;
import com.kitex.mom.plan.service.PlnSchedulingBatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 紧急插单产品闭环：真实资源探测、受影响正式计划圈定、整单可移动性检查、
 * 多工单确定性联合求解、策略阈值、A3发布、PLAN/MES补偿及受控回退。
 */
@Service
@RequiredArgsConstructor
public class PlnInsertionLocalReplanServiceImpl implements PlnInsertionLocalReplanService {
    private static final int PROPOSAL_VALID_HOURS = 24;
    private static final Set<String> URGENCY_LEVELS = Set.of("URGENT", "EMERGENCY");

    private final PlnInsertionReplanPolicyMapper policyMapper;
    private final PlnInsertionReplanCaseMapper caseMapper;
    private final PlnInsertionReplanIssueMapper impactMapper;
    private final PlnInsertionReplanLineMapper lineMapper;
    private final PlnInsertionReplanOperationMapper auditMapper;
    private final PlnSchedulingScenarioMapper scenarioMapper;
    private final PlnResourceReplanCaseMapper resourceCaseMapper;
    private final PlnShortageReplanCaseMapper shortageCaseMapper;
    private final PlnQualityReplanCaseMapper qualityCaseMapper;
    private final PlnIssueMapper issueMapper;
    private final PlnSchedulingTaskMapper taskMapper;
    private final PlnSchedulingTaskDetailMapper taskDetailMapper;
    private final PlnSchedulingOperationMapper schedulingOperationMapper;
    private final PlnSchedulingBatchMapper schedulingBatchMapper;
    private final PlnSchedulingBatchAllocationMapper schedulingBatchAllocationMapper;
    private final PlnMaterialConstraintService materialConstraintService;
    private final PlnOperationSchedulingService operationSchedulingService;
    private final PlnSchedulingBatchService schedulingBatchService;
    private final RemoteMesTaskService mesTaskService;
    private final ObjectMapper objectMapper;

    @Override
    public Page<PlnInsertionReplanPolicyEntity> policyPage(SearchPage page,
                                                           PlanInsertionLocalReplanDTO.PolicyQuery query) {
        PlanInsertionLocalReplanDTO.PolicyQuery q = query == null
                ? new PlanInsertionLocalReplanDTO.PolicyQuery() : query;
        return policyMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<PlnInsertionReplanPolicyEntity>()
                .like(StrUtil.isNotBlank(q.getPolicyCode()), PlnInsertionReplanPolicyEntity::getPolicyCode, q.getPolicyCode())
                .eq(StrUtil.isNotBlank(q.getStatus()), PlnInsertionReplanPolicyEntity::getStatus, q.getStatus())
                .orderByDesc(PlnInsertionReplanPolicyEntity::getCreateTime));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnInsertionReplanPolicyEntity savePolicyDraft(PlanInsertionLocalReplanDTO.PolicyDraftCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getPolicyCode())
                && StrUtil.isNotBlank(command.getPolicyName()), "策略编码和名称不能为空");
        String factoryCode = StrUtil.blankToDefault(command.getFactoryCode(), FactoryContextHolder.getFactoryCode());
        require(StrUtil.isNotBlank(factoryCode), "当前工厂不能为空");
        requireFactory(factoryCode);
        require(command.getEffectiveFrom() == null || command.getEffectiveTo() == null
                || command.getEffectiveTo().isAfter(command.getEffectiveFrom()), "策略失效时间必须晚于生效时间");
        String code = command.getPolicyCode().trim();
        policyMapper.lockKey(factoryCode + ':' + code);
        PlnInsertionReplanPolicyEntity entity;
        MomUser user = currentUser();
        if (command.getId() == null) {
            int version = policyMapper.selectList(new LambdaQueryWrapper<PlnInsertionReplanPolicyEntity>()
                            .eq(PlnInsertionReplanPolicyEntity::getFactoryCode, factoryCode)
                            .eq(PlnInsertionReplanPolicyEntity::getPolicyCode, code)
                            .orderByDesc(PlnInsertionReplanPolicyEntity::getPolicyVersion).last("LIMIT 1"))
                    .stream().findFirst().map(PlnInsertionReplanPolicyEntity::getPolicyVersion).orElse(0) + 1;
            entity = new PlnInsertionReplanPolicyEntity().setFactoryCode(factoryCode).setPolicyVersion(version)
                    .setStatus("DRAFT").setAuthorCode(userCode(user)).setAuthorName(userName(user));
        } else {
            entity = requirePolicy(command.getId(), true);
            require("DRAFT".equals(entity.getStatus()), "仅草稿策略允许修改");
            require(Objects.equals(factoryCode, entity.getFactoryCode()), "策略不属于当前工厂");
        }
        entity.setPolicyCode(code).setPolicyName(command.getPolicyName().trim())
                .setFreezeMinutes(bounded(command.getFreezeMinutes(), 120, 30, 10080, "冻结区必须在30分钟到7天之间"))
                .setImpactHorizonDays(bounded(command.getImpactHorizonDays(), 14, 1, 90, "影响窗口必须在1到90天之间"))
                .setMaxAffectedIssues(bounded(command.getMaxAffectedIssues(), 20, 0, 200, "受影响计划上限必须在0到200之间"))
                .setMaxMovedOperations(bounded(command.getMaxMovedOperations(), 200, 1, 5000, "移动工序上限必须在1到5000之间"))
                .setMaxTotalDelayMinutes(bounded(command.getMaxTotalDelayMinutes(), 10080, 0, 527040, "总延期上限不合法"))
                .setMaxSingleIssueDelayMinutes(bounded(command.getMaxSingleIssueDelayMinutes(), 2880, 0, 527040, "单计划延期上限不合法"))
                .setMaxInsertionLateMinutes(bounded(command.getMaxInsertionLateMinutes(), 0, 0, 527040, "插单逾期上限不合法"))
                .setMaxNewLateIssues(bounded(command.getMaxNewLateIssues(), 0, 0, 200, "新增逾期计划上限必须在0到200之间"))
                .setPriority(bounded(command.getPriority(), 100, 0, 9999, "优先级必须在0到9999之间"))
                .setEffectiveFrom(command.getEffectiveFrom()).setEffectiveTo(command.getEffectiveTo());
        entity.setPolicyFingerprint(policyFingerprint(entity));
        require(entity.getId() == null ? policyMapper.insert(entity) == 1 : policyMapper.updateById(entity) == 1,
                "紧急插单策略草稿保存失败");
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnInsertionReplanPolicyEntity activatePolicy(PlanInsertionLocalReplanDTO.PolicyActionCommand command) {
        require(command != null && command.getPolicyId() != null && StrUtil.isNotBlank(command.getRequestId()),
                "策略启用参数不完整");
        String requestId = command.getRequestId().trim();
        policyMapper.lockKey("ACTIVATE:" + requestId);
        PlnInsertionReplanPolicyEntity duplicate = policyMapper.selectOne(
                new LambdaQueryWrapper<PlnInsertionReplanPolicyEntity>()
                        .eq(PlnInsertionReplanPolicyEntity::getActivationRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getId(), command.getPolicyId()), "策略启用幂等号已被不同请求使用");
            return duplicate;
        }
        PlnInsertionReplanPolicyEntity entity = requirePolicy(command.getPolicyId(), true);
        require("DRAFT".equals(entity.getStatus()), "仅草稿策略允许启用");
        policyMapper.lockKey("ACTIVE:" + entity.getFactoryCode());
        MomUser user = currentUser();
        require(!Objects.equals(entity.getAuthorCode(), userCode(user)), "策略编制人与启用复核人必须分离");
        LocalDateTime from = Optional.ofNullable(entity.getEffectiveFrom()).orElse(LocalDateTime.now());
        boolean overlap = policyMapper.selectList(new LambdaQueryWrapper<PlnInsertionReplanPolicyEntity>()
                        .eq(PlnInsertionReplanPolicyEntity::getFactoryCode, entity.getFactoryCode())
                        .eq(PlnInsertionReplanPolicyEntity::getStatus, "ACTIVE"))
                .stream().anyMatch(item -> intervalsOverlap(from, entity.getEffectiveTo(),
                        Optional.ofNullable(item.getEffectiveFrom()).orElse(LocalDateTime.MIN), item.getEffectiveTo()));
        require(!overlap, "同工厂已有生效期重叠的紧急插单策略");
        entity.setEffectiveFrom(from).setPolicyFingerprint(policyFingerprint(entity)).setStatus("ACTIVE")
                .setActivationRequestId(requestId).setApproverCode(userCode(user)).setApproverName(userName(user))
                .setApprovedTime(LocalDateTime.now());
        require(policyMapper.updateById(entity) == 1, "紧急插单策略启用失败");
        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnInsertionReplanPolicyEntity retirePolicy(PlanInsertionLocalReplanDTO.PolicyActionCommand command) {
        require(command != null && command.getPolicyId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "策略退役参数不完整");
        String requestId = command.getRequestId().trim();
        policyMapper.lockKey("RETIRE:" + requestId);
        PlnInsertionReplanPolicyEntity duplicate = policyMapper.selectOne(
                new LambdaQueryWrapper<PlnInsertionReplanPolicyEntity>()
                        .eq(PlnInsertionReplanPolicyEntity::getRetireRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getId(), command.getPolicyId())
                            && Objects.equals(duplicate.getRetireReason(), command.getReason().trim()),
                    "策略退役幂等号已被不同请求使用");
            return duplicate;
        }
        PlnInsertionReplanPolicyEntity entity = requirePolicy(command.getPolicyId(), true);
        require("ACTIVE".equals(entity.getStatus()), "仅启用策略允许退役");
        MomUser user = currentUser();
        entity.setStatus("RETIRED").setRetireRequestId(requestId).setRetireReason(command.getReason().trim())
                .setRetiredBy(userCode(user)).setRetiredTime(LocalDateTime.now());
        require(policyMapper.updateById(entity) == 1, "紧急插单策略退役失败");
        return entity;
    }

    @Override
    public Page<PlnInsertionReplanCaseEntity> casePage(SearchPage page, PlanInsertionLocalReplanDTO.CaseQuery query) {
        caseMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PlnInsertionReplanCaseEntity>()
                .in(PlnInsertionReplanCaseEntity::getStatus, "PROPOSED", "SELECTED")
                .le(PlnInsertionReplanCaseEntity::getExpiresTime, LocalDateTime.now())
                .set(PlnInsertionReplanCaseEntity::getStatus, "EXPIRED"));
        scenarioMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PlnSchedulingScenarioEntity>()
                .eq(PlnSchedulingScenarioEntity::getTriggerType, "URGENT_INSERTION_REPLAN")
                .in(PlnSchedulingScenarioEntity::getScenarioStatus, "EVALUATED", "SELECTED")
                .le(PlnSchedulingScenarioEntity::getExpiresTime, LocalDateTime.now())
                .set(PlnSchedulingScenarioEntity::getScenarioStatus, "EXPIRED"));
        PlanInsertionLocalReplanDTO.CaseQuery q = query == null
                ? new PlanInsertionLocalReplanDTO.CaseQuery() : query;
        Page<PlnInsertionReplanCaseEntity> result = caseMapper.selectPage(page.toMpPage(), new LambdaQueryWrapper<PlnInsertionReplanCaseEntity>()
                .like(StrUtil.isNotBlank(q.getCaseNo()), PlnInsertionReplanCaseEntity::getCaseNo, q.getCaseNo())
                .like(StrUtil.isNotBlank(q.getInsertionIssueNo()), PlnInsertionReplanCaseEntity::getInsertionIssueNo, q.getInsertionIssueNo())
                .eq(StrUtil.isNotBlank(q.getStatus()), PlnInsertionReplanCaseEntity::getStatus, q.getStatus())
                .orderByDesc(PlnInsertionReplanCaseEntity::getProposedTime));
        result.getRecords().forEach(this::enrichCandidate);
        return result;
    }

    @Override
    public PlanInsertionLocalReplanDTO.CaseDetail detail(Long caseId) {
        PlnInsertionReplanCaseEntity entity = requireCase(caseId, false);
        PlanInsertionLocalReplanDTO.CaseDetail result = new PlanInsertionLocalReplanDTO.CaseDetail();
        BeanUtils.copyProperties(entity, result);
        result.setIssues(caseImpacts(caseId));
        result.setLines(caseLines(caseId));
        result.setOperations(auditMapper.selectList(new LambdaQueryWrapper<PlnInsertionReplanOperationEntity>()
                .eq(PlnInsertionReplanOperationEntity::getCaseId, caseId)
                .orderByAsc(PlnInsertionReplanOperationEntity::getOperationTime)));
        enrichCandidate(result);
        return result;
    }

    @Override
    public PlanInsertionLocalReplanDTO.Scenario scenario(Long scenarioId) {
        require(scenarioId != null, "插单重排情景不能为空");
        PlnSchedulingScenarioEntity header = scenarioMapper.selectById(scenarioId);
        require(header != null && "URGENT_INSERTION_REPLAN".equals(header.getTriggerType()), "插单重排情景不存在");
        requireFactory(header.getFactoryCode());
        List<PlanInsertionLocalReplanDTO.CaseDetail> candidates = caseMapper.selectList(
                        new LambdaQueryWrapper<PlnInsertionReplanCaseEntity>()
                                .eq(PlnInsertionReplanCaseEntity::getScenarioId, scenarioId)
                                .orderByAsc(PlnInsertionReplanCaseEntity::getId)).stream()
                .map(item -> detail(item.getId())).sorted(Comparator.comparing(
                        PlanInsertionLocalReplanDTO.CaseDetail::getCandidateRank,
                        Comparator.nullsLast(Integer::compareTo))).toList();
        return new PlanInsertionLocalReplanDTO.Scenario().setScenario(header).setCandidates(candidates);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanInsertionLocalReplanDTO.Scenario assess(PlanInsertionLocalReplanDTO.AssessCommand command) {
        validateAssess(command);
        String requestId = command.getRequestId().trim();
        String requestFingerprint = hash(json(command));
        scenarioMapper.lock("INSERTION_SCENARIO_ASSESS:" + requestId);
        PlnSchedulingScenarioEntity duplicate = scenarioMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                        .eq(PlnSchedulingScenarioEntity::getRequestId, requestId));
        if (duplicate != null) {
            require("URGENT_INSERTION_REPLAN".equals(duplicate.getTriggerType())
                            && Objects.equals(duplicate.getRequestFingerprint(), requestFingerprint),
                    "插单评估幂等号已被不同请求使用");
            return scenario(duplicate.getId());
        }
        PlnIssueEntity source = requireInsertionIssue(issueMapper.selectById(command.getInsertionIssueId()));
        requireFactory(source.getFactoryCode());
        PlnInsertionReplanPolicyEntity policy = resolvePolicy(source.getFactoryCode(), LocalDateTime.now());
        require(policy != null, "当前工厂未配置生效的紧急插单策略");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime freezeUntil = now.plusMinutes(policy.getFreezeMinutes());
        LocalDateTime requestedStart = command.getRequestedStartTime().isBefore(freezeUntil)
                ? freezeUntil : command.getRequestedStartTime();
        LocalDateTime horizonEnd;

        PlnMaterialConstraintEvaluationDTO material = materialEvidence(source, requestedStart.toLocalDate());
        require(!Boolean.TRUE.equals(material.getHardBlocked()), "插单物料硬阻断：" + material.getSummary());
        if (material.getMaterialAvailableTime() != null) {
            requestedStart = later(requestedStart, material.getMaterialAvailableTime());
        } else if (material.getMaterialAvailableDate() != null) {
            requestedStart = later(requestedStart, material.getMaterialAvailableDate().atTime(23, 59));
        }
        horizonEnd = requestedStart.plusDays(policy.getImpactHorizonDays()).with(LocalTime.MAX);
        String materialSnapshot = materialSnapshot(material);
        String materialFingerprint = hash(materialSnapshot);
        PlnIssueEntity previewInsertion = detachedIssue(source);
        applyMaterial(previewInsertion, material);

        PlnSchedulingTaskEntity probeTask = new PlnSchedulingTaskEntity();
        probeTask.setTaskNo("IRP-PROBE-" + IdUtil.getSnowflakeNextIdStr());
        probeTask.setTriggerType("URGENT_INSERTION_REPLAN_PROBE");
        probeTask.setCycleType("EVENT");
        probeTask.setMode("simulate");
        probeTask.setTaskStatus("SUCCESS");
        probeTask.setScheduleDate(requestedStart.toLocalDate());
        probeTask.setPlanCount(1);
        probeTask.setRemark("紧急插单影响范围探测，插单=" + source.getIssueNo());
        probeTask.setFactoryCode(source.getFactoryCode());
        require(taskMapper.insert(probeTask) == 1, "插单影响范围探测任务保存失败");

        operationSchedulingService.previewInsertionReplan(probeTask.getId(), List.of(previewInsertion),
                Map.of(), Map.of(source.getId(), requestedStart));
        List<PlnSchedulingOperationEntity> probe = proposalOperations(probeTask.getId(), Set.of(source.getId()));
        require(!probe.isEmpty(), "插单未生成可行的工序级探测排程");

        List<PlnSchedulingOperationEntity> horizonLocked = schedulingOperationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getFactoryCode, source.getFactoryCode())
                        .eq(PlnSchedulingOperationEntity::getLockStatus, true)
                        .ne(PlnSchedulingOperationEntity::getIssueId, source.getId())
                        .lt(PlnSchedulingOperationEntity::getScheduledStartTime, horizonEnd)
                        .gt(PlnSchedulingOperationEntity::getScheduledEndTime, requestedStart)
                        .orderByAsc(PlnSchedulingOperationEntity::getScheduledStartTime));
        LocalDateTime effectiveRequestedStart = requestedStart;
        Set<Long> affectedIds = horizonLocked.stream()
                .filter(row -> conflictsWithShiftedProbe(row, probe, effectiveRequestedStart))
                .map(PlnSchedulingOperationEntity::getIssueId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        require(affectedIds.size() <= policy.getMaxAffectedIssues(), "受影响计划" + affectedIds.size()
                + "张，超过策略上限" + policy.getMaxAffectedIssues() + "张，必须扩大情景排产或调整交期");

        List<Long> scopeIds = new ArrayList<>(affectedIds);
        scopeIds.add(source.getId());
        scopeIds = scopeIds.stream().distinct().sorted().toList();
        Map<Long, PlnIssueEntity> issues = new LinkedHashMap<>();
        for (Long id : scopeIds) issues.put(id, lockIssue(id));
        PlnIssueEntity insertion = requireInsertionIssue(issues.get(source.getId()));
        require(Objects.equals(issueFingerprint(insertion), issueFingerprint(source)), "插单计划在评估过程中已变化，请重试");
        for (Long id : scopeIds) ensureNoLiveProposal(id);

        Map<Long, List<PlnSchedulingOperationEntity>> originals = new LinkedHashMap<>();
        Map<Long, Long> excludedTasks = new LinkedHashMap<>();
        for (Long id : affectedIds.stream().sorted().toList()) {
            PlnIssueEntity issue = issues.get(id);
            require(issue.getDemandTime() != null, "受影响计划[" + issue.getIssueNo() + "]缺少需求日期，不能计算插单交期损失");
            List<PlnSchedulingOperationEntity> rows = lockedOperations(issue);
            require(rows.stream().allMatch(item -> item.getScheduledStartTime() != null
                            && item.getScheduledStartTime().isAfter(freezeUntil)),
                    "计划[" + issue.getIssueNo() + "]存在冻结区内工序，不能作为整单联合重排对象");
            originals.put(id, rows);
            excludedTasks.put(id, issue.getLockTaskId());
        }

        Map<Long, MesApsLocalScheduleDTO.StateBundle> mesBefore = new LinkedHashMap<>();
        for (Long id : scopeIds) {
            MesApsLocalScheduleDTO.StateBundle state = mesStates(issues.get(id));
            if (affectedIds.contains(id)) validateMesMovable(state,
                    PlnFrozenRouteNodeIdentity.correspondence(originals.get(id), state).keySet());
            mesBefore.put(id, state);
        }
        require(!Boolean.TRUE.equals(mesBefore.get(insertion.getId()).getMesTaskPresent()),
                "未下发插单已存在MES工单，主数据状态不一致，必须先完成计划/MES对账");

        applyMaterial(insertion, material);
        Map<Long, LocalDateTime> requested = new LinkedHashMap<>();
        requested.put(insertion.getId(), requestedStart);
        for (Long id : affectedIds) requested.put(id, firstStart(originals.get(id)));
        String mesSnapshot = json(mesBefore);
        String mesFingerprint = aggregateMesFingerprint(mesBefore);
        String originalSnapshot = json(originals);
        String originalFingerprint = aggregateScheduleFingerprint(originals);
        MomUser user = currentUser();
        String sourceFingerprint = hash(scopeIds.stream().map(issues::get).map(this::issueFingerprint)
                .collect(Collectors.joining("|")) + '|' + policy.getPolicyFingerprint() + '|'
                + materialFingerprint + '|' + mesFingerprint + '|' + originalFingerprint);
        PlnSchedulingScenarioEntity header = new PlnSchedulingScenarioEntity();
        header.setScenarioNo("IRS-" + IdUtil.getSnowflakeNextIdStr());
        header.setRequestId(requestId);
        header.setRequestFingerprint(requestFingerprint);
        header.setTriggerType("URGENT_INSERTION_REPLAN");
        header.setCycleType("EVENT");
        header.setScheduleDate(requestedStart.toLocalDate());
        header.setPlanNum(scopeIds.size());
        header.setSourceIssueIdsSnapshot(json(scopeIds));
        header.setSourceFingerprint(sourceFingerprint);
        header.setRuleCode(policy.getPolicyCode());
        header.setRuleVersion(policy.getPolicyVersion());
        header.setScenarioStatus("EVALUATING");
        header.setCandidateCount(3);
        header.setExpiresTime(now.plusHours(PROPOSAL_VALID_HOURS));
        header.setFactoryCode(insertion.getFactoryCode());
        require(scenarioMapper.insert(header) == 1, "紧急插单多候选情景保存失败");

        schedulingOperationMapper.delete(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getTaskId, probeTask.getId()));
        taskMapper.deleteById(probeTask.getId());
        List<InsertionCandidateProfile> profiles = List.of(
                new InsertionCandidateProfile("RECOVERY", "插单交期优先", "RECOVERY_FIRST", "DELIVERY_FIRST"),
                new InsertionCandidateProfile("STABILITY", "最小扰动", "MINIMUM_DISRUPTION", "BALANCED_GLOBAL"),
                new InsertionCandidateProfile("CHANGEOVER", "换型稳定", "CHANGEOVER_STABLE", "CHANGEOVER_FIRST"));
        List<InsertionCandidateBuild> builds = new ArrayList<>();
        for (InsertionCandidateProfile profile : profiles) {
            builds.add(buildInsertionCandidate(header, profile, command, requestFingerprint, insertion,
                    affectedIds, scopeIds, issues, originals, excludedTasks, requested, mesBefore,
                    materialSnapshot, materialFingerprint, mesSnapshot, mesFingerprint, originalSnapshot,
                    originalFingerprint, freezeUntil, horizonEnd, policy, user, now));
        }
        rankInsertionCandidates(builds);
        header.setScenarioStatus("EVALUATED");
        require(scenarioMapper.updateById(header) == 1, "紧急插单多候选情景评估状态保存失败");
        return scenario(header.getId());
    }

    private InsertionCandidateBuild buildInsertionCandidate(PlnSchedulingScenarioEntity header,
                                                            InsertionCandidateProfile profile, PlanInsertionLocalReplanDTO.AssessCommand command,
                                                            String requestFingerprint, PlnIssueEntity insertion, Set<Long> affectedIds, List<Long> scopeIds,
                                                            Map<Long, PlnIssueEntity> issues, Map<Long, List<PlnSchedulingOperationEntity>> originals,
                                                            Map<Long, Long> excludedTasks, Map<Long, LocalDateTime> requested,
                                                            Map<Long, MesApsLocalScheduleDTO.StateBundle> mesBefore, String materialSnapshot,
                                                            String materialFingerprint, String mesSnapshot, String mesFingerprint, String originalSnapshot,
                                                            String originalFingerprint, LocalDateTime freezeUntil, LocalDateTime horizonEnd,
                                                            PlnInsertionReplanPolicyEntity policy, MomUser user, LocalDateTime now) {
        PlnSchedulingTaskEntity task = new PlnSchedulingTaskEntity();
        task.setTaskNo("IRP-" + IdUtil.getSnowflakeNextIdStr());
        task.setTriggerType("URGENT_INSERTION_REPLAN");
        task.setCycleType("EVENT");
        task.setMode("simulate");
        task.setTaskStatus("EVALUATING");
        task.setScheduleDate(requested.get(insertion.getId()).toLocalDate());
        task.setPlanCount(scopeIds.size());
        task.setScenarioId(header.getId());
        task.setCandidateCode(profile.code());
        task.setCandidateName(profile.name());
        task.setSequenceStrategy(profile.sequenceStrategy());
        task.setCrossPriorityWindowDays(0);
        task.setObjectiveWeightsSnapshot(json(Map.of("localReplanStrategy", profile.solverStrategy(),
                "objective", profile.name(), "hardConstraintsRelaxed", false,
                "fixedImpactScope", scopeIds, "sourceFingerprint", header.getSourceFingerprint())));
        task.setSelectedCandidate(false);
        task.setRemark("紧急插单情景" + header.getScenarioNo()
                + "候选[" + profile.name() + "]，插单=" + insertion.getIssueNo());
        task.setFactoryCode(insertion.getFactoryCode());
        require(taskMapper.insert(task) == 1, "紧急插单候选任务保存失败");

        List<PlnIssueEntity> ordered = new ArrayList<>();
        ordered.add(detachedIssue(insertion));
        Comparator<PlnIssueEntity> affectedOrder = "CHANGEOVER".equals(profile.code())
                ? Comparator.comparing(PlnIssueEntity::getProductCode, Comparator.nullsLast(String::compareTo))
                .thenComparing(item -> firstStart(originals.get(item.getId())))
                : Comparator.comparing(item -> firstStart(originals.get(item.getId())));
        affectedIds.stream().map(issues::get).map(this::detachedIssue).sorted(affectedOrder).forEach(ordered::add);
        operationSchedulingService.previewInsertionReplan(task.getId(), ordered, excludedTasks, requested,
                profile.solverStrategy());
        Map<Long, List<PlnSchedulingOperationEntity>> candidates = proposalOperations(task.getId(),
                new LinkedHashSet<>(scopeIds)).stream().collect(Collectors.groupingBy(
                PlnSchedulingOperationEntity::getIssueId, LinkedHashMap::new, Collectors.toList()));
        require(candidates.keySet().containsAll(scopeIds), "联合重排候选未覆盖固定插单影响范围");
        operationSchedulingService.validateInsertionReplan(issues, candidates, excludedTasks);
        Metrics metrics = metrics(insertion, affectedIds, issues, originals, candidates);
        boolean withinHorizon = !metrics.insertionCompletion.isAfter(horizonEnd);
        boolean feasible = withinHorizon && metrics.movedOperations <= policy.getMaxMovedOperations()
                && metrics.totalDelay <= policy.getMaxTotalDelayMinutes()
                && metrics.maxDelay <= policy.getMaxSingleIssueDelayMinutes()
                && metrics.insertionLate <= policy.getMaxInsertionLateMinutes()
                && metrics.newLate <= policy.getMaxNewLateIssues();
        long totalShift = insertionTotalShift(affectedIds, originals, candidates);
        long changeoverMinutes = candidates.values().stream().flatMap(Collection::stream)
                .map(PlnSchedulingOperationEntity::getChangeoverMinutes).filter(Objects::nonNull)
                .mapToLong(Long::longValue).sum();
        Map<String, Object> metricSnapshot = new LinkedHashMap<>();
        metricSnapshot.put("affectedIssueCount", affectedIds.size());
        metricSnapshot.put("movedIssueCount", metrics.movedIssues);
        metricSnapshot.put("movedOperationCount", metrics.movedOperations);
        metricSnapshot.put("resourceChangeCount", metrics.reassignedOperations);
        metricSnapshot.put("newLateIssueCount", metrics.newLate);
        metricSnapshot.put("totalDelayMinutes", metrics.totalDelay);
        metricSnapshot.put("maximumDelayMinutes", metrics.maxDelay);
        metricSnapshot.put("insertionLateMinutes", metrics.insertionLate);
        metricSnapshot.put("insertionCompletionTime", metrics.insertionCompletion);
        metricSnapshot.put("totalShiftMinutes", totalShift);
        metricSnapshot.put("changeoverMinutes", changeoverMinutes);
        metricSnapshot.put("withinImpactHorizon", withinHorizon);
        metricSnapshot.put("policyFeasible", feasible);
        metricSnapshot.put("maxAffectedIssues", policy.getMaxAffectedIssues());
        metricSnapshot.put("maxMovedOperations", policy.getMaxMovedOperations());
        metricSnapshot.put("maxTotalDelayMinutes", policy.getMaxTotalDelayMinutes());
        metricSnapshot.put("maxSingleIssueDelayMinutes", policy.getMaxSingleIssueDelayMinutes());
        metricSnapshot.put("maxInsertionLateMinutes", policy.getMaxInsertionLateMinutes());
        metricSnapshot.put("maxNewLateIssues", policy.getMaxNewLateIssues());
        task.setCandidateMetricsSnapshot(json(metricSnapshot));
        task.setCandidateFeasible(feasible);
        task.setCandidateScore(feasible ? insertionCandidateScore(metrics, totalShift, changeoverMinutes)
                : BigDecimal.ZERO.setScale(2));
        task.setCandidateRank(1);
        task.setEvaluatedTime(LocalDateTime.now());
        task.setTaskStatus("SUCCESS");
        require(taskMapper.updateById(task) == 1, "紧急插单候选指标保存失败");

        String proposedSnapshot = json(candidates);
        String proposedFingerprint = aggregateScheduleFingerprint(candidates);
        String proposalFingerprint = hash(issueFingerprint(insertion) + '|' + policy.getPolicyFingerprint() + '|'
                + materialFingerprint + '|' + mesFingerprint + '|' + originalFingerprint + '|' + proposedFingerprint);
        PlnInsertionReplanCaseEntity entity = new PlnInsertionReplanCaseEntity()
                .setCaseNo("IRC-" + IdUtil.getSnowflakeNextIdStr()).setScenarioId(header.getId())
                .setAssessRequestId(command.getRequestId().trim() + ':' + profile.code())
                .setAssessRequestFingerprint(requestFingerprint).setInsertionIssueId(insertion.getId())
                .setInsertionIssueNo(insertion.getIssueNo()).setProductCode(insertion.getProductCode())
                .setProductName(insertion.getProductName()).setUrgencyLevel(command.getUrgencyLevel().trim().toUpperCase())
                .setRequestedStartTime(requested.get(insertion.getId())).setBusinessReason(command.getBusinessReason().trim())
                .setPolicyId(policy.getId()).setPolicyCode(policy.getPolicyCode()).setPolicyVersion(policy.getPolicyVersion())
                .setPolicyFingerprint(policy.getPolicyFingerprint()).setFreezeUntil(freezeUntil).setImpactHorizonEnd(horizonEnd)
                .setMaxAffectedIssues(policy.getMaxAffectedIssues()).setMaxMovedOperations(policy.getMaxMovedOperations())
                .setMaxTotalDelayMinutes(policy.getMaxTotalDelayMinutes()).setMaxSingleIssueDelayMinutes(policy.getMaxSingleIssueDelayMinutes())
                .setMaxInsertionLateMinutes(policy.getMaxInsertionLateMinutes()).setMaxNewLateIssues(policy.getMaxNewLateIssues())
                .setProposalTaskId(task.getId()).setAffectedIssueCount(affectedIds.size()).setMovedIssueCount(metrics.movedIssues)
                .setMovedOperationCount(metrics.movedOperations).setReassignedOperationCount(metrics.reassignedOperations)
                .setNewLateIssueCount(metrics.newLate).setTotalDelayMinutes(metrics.totalDelay).setMaximumDelayMinutes(metrics.maxDelay)
                .setInsertionLateMinutes(metrics.insertionLate).setInsertionCompletionTime(metrics.insertionCompletion)
                .setDecisionResult(affectedIds.isEmpty() ? "ACCEPT_DIRECT" : "ACCEPT_WITH_IMPACT")
                .setSourceIssueFingerprint(issueFingerprint(insertion)).setMaterialEvidenceSnapshot(materialSnapshot)
                .setMaterialEvidenceFingerprint(materialFingerprint).setMesExecutionSnapshot(mesSnapshot)
                .setMesExecutionFingerprint(mesFingerprint).setOriginalScheduleSnapshot(originalSnapshot)
                .setOriginalScheduleFingerprint(originalFingerprint).setProposedScheduleSnapshot(proposedSnapshot)
                .setProposalFingerprint(proposalFingerprint).setApplicantCode(userCode(user)).setApplicantName(userName(user))
                .setProposedTime(now).setExpiresTime(now.plusHours(PROPOSAL_VALID_HOURS)).setStatus("PROPOSED")
                .setFactoryCode(insertion.getFactoryCode());
        require(caseMapper.insert(entity) == 1, "紧急插单候选案件保存失败");
        saveImpactsAndLines(entity, insertion, affectedIds, issues, originals, candidates, mesBefore);
        for (PlnIssueEntity issue : ordered) saveProposalDetail(task, issue, candidates.get(issue.getId()));
        audit(entity, entity.getAssessRequestId(), "ASSESS_PROPOSE", null, "PROPOSED", "候选[" + profile.name()
                + "]：插单1张、固定影响计划" + affectedIds.size() + "张、移动工序" + metrics.movedOperations
                + "道、总延期" + metrics.totalDelay + "分钟" + (feasible ? "" : "，超过治理阈值，不可选择"));
        return new InsertionCandidateBuild(entity, task, metrics, totalShift, changeoverMinutes);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanInsertionLocalReplanDTO.Scenario select(PlanInsertionLocalReplanDTO.SelectCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getRequestId()) && command.getCandidateCaseId() != null
                        && StrUtil.isNotBlank(command.getExpectedSourceFingerprint()) && StrUtil.isNotBlank(command.getReason()),
                "紧急插单候选选择参数不完整");
        PlnInsertionReplanCaseEntity selectedCase = requireCase(command.getCandidateCaseId(), false);
        require(selectedCase.getScenarioId() != null, "历史单方案不需要候选选择");
        scenarioMapper.lock("INSERTION_SCENARIO_SELECT:" + selectedCase.getScenarioId());
        PlnSchedulingScenarioEntity header = scenarioMapper.selectOne(new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                .eq(PlnSchedulingScenarioEntity::getId, selectedCase.getScenarioId()).last("FOR UPDATE"));
        require(header != null && "URGENT_INSERTION_REPLAN".equals(header.getTriggerType()), "紧急插单多候选情景不存在");
        String selectionFingerprint = hash(command.getRequestId().trim() + '|' + command.getCandidateCaseId()
                + '|' + command.getExpectedSourceFingerprint() + '|' + command.getReason().trim());
        if (Set.of("SELECTED", "PUBLISHED").contains(header.getScenarioStatus())) {
            require(Objects.equals(header.getSelectionRequestId(), command.getRequestId().trim())
                    && Objects.equals(header.getSelectionFingerprint(), selectionFingerprint), "该插单情景已完成选择，不允许覆盖审计结论");
            return scenario(header.getId());
        }
        require("EVALUATED".equals(header.getScenarioStatus()) && header.getExpiresTime().isAfter(LocalDateTime.now()),
                "当前插单情景不可选择或已过期");
        require(Objects.equals(header.getSourceFingerprint(), command.getExpectedSourceFingerprint()), "插单情景来源指纹不一致");
        List<PlnInsertionReplanCaseEntity> cases = caseMapper.selectList(new LambdaQueryWrapper<PlnInsertionReplanCaseEntity>()
                .eq(PlnInsertionReplanCaseEntity::getScenarioId, header.getId()).last("FOR UPDATE"));
        require(cases.size() == header.getCandidateCount(), "插单情景候选数量不完整");
        List<PlnSchedulingTaskEntity> tasks = taskMapper.selectList(new LambdaQueryWrapper<PlnSchedulingTaskEntity>()
                .eq(PlnSchedulingTaskEntity::getScenarioId, header.getId()));
        PlnSchedulingTaskEntity selectedTask = tasks.stream().filter(item -> Objects.equals(item.getId(), selectedCase.getProposalTaskId()))
                .findFirst().orElseThrow(() -> new CheckedException("候选任务不属于当前插单情景"));
        require(Boolean.TRUE.equals(selectedTask.getCandidateFeasible()), "超过治理阈值的候选不能进入发布链");
        cases.forEach(item -> {
            item.setStatus(Objects.equals(item.getId(), selectedCase.getId()) ? "SELECTED" : "SUPERSEDED");
            require(caseMapper.updateById(item) == 1, "插单候选选择状态保存失败");
        });
        tasks.forEach(item -> {
            boolean selected = Objects.equals(item.getId(), selectedTask.getId());
            item.setSelectedCandidate(selected);
            if (!selected) item.setTaskStatus("SUPERSEDED");
            require(taskMapper.updateById(item) == 1, "插单候选任务选择状态保存失败");
        });
        header.setSelectedTaskId(selectedTask.getId());
        header.setSelectionRequestId(command.getRequestId().trim());
        header.setSelectionFingerprint(selectionFingerprint);
        header.setSelectionReason(command.getReason().trim());
        header.setSelectedBy(userCode(currentUser()));
        header.setSelectedTime(LocalDateTime.now());
        header.setScenarioStatus("SELECTED");
        require(scenarioMapper.updateById(header) == 1, "插单候选选择审计保存失败");
        audit(selectedCase, command.getRequestId(), "SELECT", "PROPOSED", "SELECTED",
                "从三个跨工单有限产能候选中选择[" + selectedTask.getCandidateName() + "]：" + command.getReason().trim());
        return scenario(header.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanInsertionLocalReplanDTO.PublishReceipt publish(PlanInsertionLocalReplanDTO.PublishCommand command) {
        require(command != null && command.getCaseId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getExpectedProposalFingerprint())
                && StrUtil.isNotBlank(command.getExpectedMaterialEvidenceFingerprint())
                && StrUtil.isNotBlank(command.getExpectedMesExecutionFingerprint()), "插单发布参数不完整");
        String requestId = command.getRequestId().trim();
        caseMapper.lockKey("PUBLISH:" + requestId);
        PlnInsertionReplanCaseEntity duplicate = caseMapper.selectOne(new LambdaQueryWrapper<PlnInsertionReplanCaseEntity>()
                .eq(PlnInsertionReplanCaseEntity::getPublishRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getId(), command.getCaseId())
                            && Objects.equals(duplicate.getProposalFingerprint(), command.getExpectedProposalFingerprint())
                            && Objects.equals(duplicate.getMaterialEvidenceFingerprint(), command.getExpectedMaterialEvidenceFingerprint())
                            && Objects.equals(duplicate.getMesExecutionFingerprint(), command.getExpectedMesExecutionFingerprint()),
                    "插单发布幂等号已被不同请求使用");
            return receipt(duplicate, duplicate.getMesPublishedFingerprint());
        }
        PlnInsertionReplanCaseEntity entity = requireCase(command.getCaseId(), true);
        String beforeStatus = entity.getStatus();
        if (entity.getScenarioId() == null)
            require("PROPOSED".equals(entity.getStatus()), "仅待发布紧急插单候选允许发布");
        else {
            require("SELECTED".equals(entity.getStatus()), "多候选插单情景必须先完成A2选择");
            PlnSchedulingScenarioEntity header = scenarioMapper.selectById(entity.getScenarioId());
            require(header != null && "SELECTED".equals(header.getScenarioStatus())
                    && Objects.equals(header.getSelectedTaskId(), entity.getProposalTaskId()), "插单情景选择审计不一致");
        }
        require(entity.getExpiresTime().isAfter(LocalDateTime.now()), "插单候选已过期，必须重新评估");
        require(Objects.equals(entity.getProposalFingerprint(), command.getExpectedProposalFingerprint())
                        && Objects.equals(entity.getMaterialEvidenceFingerprint(), command.getExpectedMaterialEvidenceFingerprint())
                        && Objects.equals(entity.getMesExecutionFingerprint(), command.getExpectedMesExecutionFingerprint()),
                "审批冻结的方案、物料或MES指纹不一致");
        MomUser user = currentUser();
        require(!Objects.equals(entity.getApplicantCode(), userCode(user)), "A3紧急插单禁止申请人自批并发布");
        PlnInsertionReplanPolicyEntity policy = requirePolicy(entity.getPolicyId(), false);
        require("ACTIVE".equals(policy.getStatus()) && isEffective(policy, LocalDateTime.now())
                && Objects.equals(policy.getPolicyFingerprint(), entity.getPolicyFingerprint()), "插单策略已失效或变化");

        List<PlnInsertionReplanIssueEntity> impacts = caseImpacts(entity.getId());
        List<Long> ids = impacts.stream().map(PlnInsertionReplanIssueEntity::getIssueId).sorted().toList();
        Map<Long, PlnIssueEntity> issues = new LinkedHashMap<>();
        for (Long id : ids) issues.put(id, lockIssue(id));
        PlnIssueEntity insertion = requireInsertionIssue(issues.get(entity.getInsertionIssueId()));
        PlnMaterialConstraintEvaluationDTO material = materialEvidence(insertion, entity.getRequestedStartTime().toLocalDate());
        String materialFingerprint = hash(materialSnapshot(material));
        if (!Objects.equals(entity.getSourceIssueFingerprint(), issueFingerprint(insertion))
                || !Objects.equals(entity.getMaterialEvidenceFingerprint(), materialFingerprint)
                || Boolean.TRUE.equals(material.getHardBlocked())) {
            markStale(entity, requestId, user, "插单计划或物料齐套事实已变化");
            return receipt(entity, null);
        }
        Map<Long, List<PlnSchedulingOperationEntity>> originals = new LinkedHashMap<>();
        Map<Long, Long> excludedTasks = new LinkedHashMap<>();
        for (PlnInsertionReplanIssueEntity impact : impacts) {
            if (Boolean.TRUE.equals(impact.getInsertionIssue())) continue;
            PlnIssueEntity issue = issues.get(impact.getIssueId());
            List<PlnSchedulingOperationEntity> rows = lockedOperations(issue);
            originals.put(issue.getId(), rows);
            excludedTasks.put(issue.getId(), issue.getLockTaskId());
        }
        if (!Objects.equals(entity.getOriginalScheduleFingerprint(), aggregateScheduleFingerprint(originals))) {
            markStale(entity, requestId, user, "被挤占计划的正式APS已变化");
            return receipt(entity, null);
        }
        Map<Long, MesApsLocalScheduleDTO.StateBundle> mesBefore = captureMes(issues);
        require(!Boolean.TRUE.equals(mesBefore.get(insertion.getId()).getMesTaskPresent()),
                "未下发插单已出现MES工单，禁止发布并要求先对账");
        if (!Objects.equals(entity.getMesExecutionFingerprint(), aggregateMesFingerprint(mesBefore))) {
            markStale(entity, requestId, user, "MES执行或派工事实已变化");
            return receipt(entity, null);
        }
        for (PlnInsertionReplanIssueEntity impact : impacts) {
            if (!Boolean.TRUE.equals(impact.getInsertionIssue())) validateMesMovable(mesBefore.get(impact.getIssueId()),
                    PlnFrozenRouteNodeIdentity.correspondence(originals.get(impact.getIssueId()), mesBefore.get(impact.getIssueId())).keySet());
        }
        Map<Long, List<PlnSchedulingOperationEntity>> candidates = verifiedCandidates(entity, impacts);
        require(Objects.equals(entity.getProposalFingerprint(), hash(entity.getSourceIssueFingerprint() + '|'
                + entity.getPolicyFingerprint() + '|' + entity.getMaterialEvidenceFingerprint() + '|'
                + entity.getMesExecutionFingerprint() + '|' + entity.getOriginalScheduleFingerprint() + '|'
                + aggregateScheduleFingerprint(candidates))), "插单候选工序快照已变化");
        operationSchedulingService.validateInsertionReplan(issues, candidates, excludedTasks);

        List<MesSync> synced = new ArrayList<>();
        try {
            for (PlnInsertionReplanIssueEntity impact : impacts) {
                Long id = impact.getIssueId();
                MesApsLocalScheduleDTO.StateBundle state = mesBefore.get(id);
                if (Boolean.TRUE.equals(state.getMesTaskPresent())) {
                    List<PlnSchedulingOperationEntity> rollbackRows = originals.getOrDefault(id, List.of());
                    MesApsLocalScheduleDTO.ApplyReceipt applied = applyMes(requestId + ":MES:" + id, entity.getId(),
                            issues.get(id), state.getExecutionFingerprint(), candidates.get(id));
                    synced.add(new MesSync(issues.get(id), applied.getAfterExecutionFingerprint(), rollbackRows));
                    impact.setMesPublishedFingerprint(applied.getAfterExecutionFingerprint());
                } else {
                    impact.setMesPublishedFingerprint(state.getExecutionFingerprint());
                }
            }

            PlnSchedulingTaskEntity official = createOfficialTask(entity, insertion, candidates.get(insertion.getId()));
            for (PlnInsertionReplanIssueEntity impact : impacts) {
                if (Boolean.TRUE.equals(impact.getInsertionIssue())) continue;
                List<PlnSchedulingOperationEntity> formal = originals.get(impact.getIssueId());
                Map<String, PlnSchedulingOperationEntity> byCode = uniqueByProcess(candidates.get(impact.getIssueId()));
                for (PlnSchedulingOperationEntity row : formal) {
                    copySchedule(byCode.get(PlnFrozenRouteNodeIdentity.key(row)), row);
                    require(schedulingOperationMapper.updateById(row) == 1, "被挤占计划正式工序写回失败");
                }
                refreshFormalBatchSummaries(formal);
                updateFormalSummary(issues.get(impact.getIssueId()), formal, "紧急插单联合重排已复核发布");
            }
            lockInsertionIssue(insertion, official, candidates.get(insertion.getId()));
            String publishedSnapshot = json(impacts.stream().collect(Collectors.toMap(
                    PlnInsertionReplanIssueEntity::getIssueId, PlnInsertionReplanIssueEntity::getMesPublishedFingerprint,
                    (a, b) -> a, LinkedHashMap::new)));
            String publishedFingerprint = hash(publishedSnapshot);
            for (PlnInsertionReplanIssueEntity impact : impacts)
                require(impactMapper.updateById(impact) == 1,
                        "插单影响计划MES发布指纹保存失败");
            entity.setStatus("PUBLISHED").setPublishedTaskId(official.getId()).setPublishRequestId(requestId)
                    .setPublisherCode(userCode(user)).setPublisherName(userName(user)).setPublishedTime(LocalDateTime.now())
                    .setMesPublishedSnapshot(publishedSnapshot).setMesPublishedFingerprint(publishedFingerprint);
            require(caseMapper.updateById(entity) == 1, "紧急插单发布状态保存失败");
            if (entity.getScenarioId() != null) {
                PlnSchedulingScenarioEntity header = scenarioMapper.selectById(entity.getScenarioId());
                header.setScenarioStatus("PUBLISHED");
                require(scenarioMapper.updateById(header) == 1, "紧急插单情景发布状态保存失败");
            }
            audit(entity, requestId, "PUBLISH", beforeStatus, "PUBLISHED", "插单正式锁定并联合写回"
                    + entity.getAffectedIssueCount() + "张被挤占计划，PLAN/MES已同步");
            return receipt(entity, publishedFingerprint);
        } catch (RuntimeException ex) {
            compensateMes(requestId + ":COMPENSATE", entity.getId(), synced, ex);
            throw ex;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(PlanInsertionLocalReplanDTO.CancelCommand command) {
        require(command != null && command.getCaseId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()), "取消参数不完整");
        String requestId = command.getRequestId().trim();
        caseMapper.lockKey("CANCEL:" + requestId);
        PlnInsertionReplanCaseEntity duplicate = caseMapper.selectOne(new LambdaQueryWrapper<PlnInsertionReplanCaseEntity>()
                .eq(PlnInsertionReplanCaseEntity::getCancelRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getId(), command.getCaseId())
                    && Objects.equals(duplicate.getCancelReason(), command.getReason().trim()), "取消幂等号已被不同请求使用");
            return true;
        }
        PlnInsertionReplanCaseEntity entity = requireCase(command.getCaseId(), true);
        require("PROPOSED".equals(entity.getStatus()), "仅尚未选择的插单情景允许取消");
        MomUser user = currentUser();
        List<PlnInsertionReplanCaseEntity> targets = entity.getScenarioId() == null ? List.of(entity)
                : caseMapper.selectList(new LambdaQueryWrapper<PlnInsertionReplanCaseEntity>()
                .eq(PlnInsertionReplanCaseEntity::getScenarioId, entity.getScenarioId()).last("FOR UPDATE"));
        for (PlnInsertionReplanCaseEntity target : targets) {
            target.setStatus("CANCELLED").setCancelRequestId(requestId + (Objects.equals(target.getId(), entity.getId()) ? "" : ":" + target.getId()))
                    .setCancelReason(command.getReason().trim()).setCancelledBy(userCode(user))
                    .setCancelledByName(userName(user)).setCancelledTime(LocalDateTime.now());
            require(caseMapper.updateById(target) == 1, "插单候选取消失败");
            audit(target, target.getCancelRequestId(), "CANCEL", "PROPOSED", "CANCELLED", command.getReason().trim());
        }
        if (entity.getScenarioId() != null) {
            PlnSchedulingScenarioEntity header = scenarioMapper.selectById(entity.getScenarioId());
            require(header != null && "EVALUATED".equals(header.getScenarioStatus()), "仅尚未选择的插单情景允许取消");
            header.setScenarioStatus("CANCELLED");
            require(scenarioMapper.updateById(header) == 1, "插单情景取消失败");
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanInsertionLocalReplanDTO.PublishReceipt rollback(PlanInsertionLocalReplanDTO.RollbackCommand command) {
        require(command != null && command.getCaseId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getReason()) && StrUtil.isNotBlank(command.getExpectedProposalFingerprint())
                && StrUtil.isNotBlank(command.getExpectedMesExecutionFingerprint()), "插单回退参数不完整");
        String requestId = command.getRequestId().trim();
        caseMapper.lockKey("ROLLBACK:" + requestId);
        PlnInsertionReplanCaseEntity duplicate = caseMapper.selectOne(new LambdaQueryWrapper<PlnInsertionReplanCaseEntity>()
                .eq(PlnInsertionReplanCaseEntity::getRollbackRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getId(), command.getCaseId())
                            && Objects.equals(duplicate.getProposalFingerprint(), command.getExpectedProposalFingerprint())
                            && Objects.equals(duplicate.getMesPublishedFingerprint(), command.getExpectedMesExecutionFingerprint())
                            && Objects.equals(duplicate.getRollbackReason(), command.getReason().trim()),
                    "插单回退幂等号已被不同请求使用");
            return receipt(duplicate, duplicate.getMesRollbackFingerprint());
        }
        PlnInsertionReplanCaseEntity entity = requireCase(command.getCaseId(), true);
        require("PUBLISHED".equals(entity.getStatus()), "仅已发布且未回退插单允许回退");
        require(Objects.equals(entity.getProposalFingerprint(), command.getExpectedProposalFingerprint())
                        && Objects.equals(entity.getMesPublishedFingerprint(), command.getExpectedMesExecutionFingerprint()),
                "回退指纹与已发布插单不一致");
        MomUser user = currentUser();
        require(!Objects.equals(entity.getPublisherCode(), userCode(user)), "插单发布人与回退复核人必须分离");
        List<PlnInsertionReplanIssueEntity> impacts = caseImpacts(entity.getId());
        Map<Long, PlnIssueEntity> issues = new LinkedHashMap<>();
        for (Long id : impacts.stream().map(PlnInsertionReplanIssueEntity::getIssueId).sorted().toList())
            issues.put(id, lockIssue(id));
        PlnIssueEntity insertion = issues.get(entity.getInsertionIssueId());
        require(IssueStatusEnum.UNISSUED.getCode().equals(insertion.getStatus()),
                "插单已下发、生产或关闭，必须走计划撤回/停工流程，不能直接回退排程");

        Map<Long, List<PlnSchedulingOperationEntity>> current = new LinkedHashMap<>();
        Map<Long, List<PlnSchedulingOperationEntity>> rollbackRows = new LinkedHashMap<>();
        Map<Long, Long> excluded = new LinkedHashMap<>();
        for (PlnInsertionReplanIssueEntity impact : impacts) {
            List<PlnSchedulingOperationEntity> rows = lockedOperations(issues.get(impact.getIssueId()));
            current.put(impact.getIssueId(), rows);
            excluded.put(impact.getIssueId(), issues.get(impact.getIssueId()).getLockTaskId());
            require(Objects.equals(scheduleStateFingerprint(rows), impact.getProposedScheduleFingerprint()),
                    "计划[" + impact.getIssueNo() + "]发布后已再次排程，禁止覆盖回退");
            if (!Boolean.TRUE.equals(impact.getInsertionIssue())) {
                var originalRows = readOperations(impact.getOriginalScheduleSnapshot());
                PlnFrozenRouteNodeIdentity.replacementSet(originalRows, rows);
                var originalByNode = uniqueByProcess(originalRows);
                for (var line : caseLines(entity.getId())) {
                    if (!Objects.equals(line.getIssueId(), impact.getIssueId())) continue;
                    String key = PlnFrozenRouteNodeIdentity.lineKey(line.getOriginalOperationId(), line.getSourceRouteNodeCode(), line.getProcessCode(), originalByNode);
                    var now = uniqueByProcess(rows).get(key);
                    require(Objects.equals(now.getId(), line.getOriginalOperationId()), "插单回退当前主键已不同于原工序");
                }
                rollbackRows.put(impact.getIssueId(), originalRows);
            }
        }
        Map<Long, MesApsLocalScheduleDTO.StateBundle> mesNow = captureMes(issues);
        require(!Boolean.TRUE.equals(mesNow.get(insertion.getId()).getMesTaskPresent()),
                "插单已形成MES工单，必须走受控撤回/停工流程，不能直接回退排程");
        String publishedSnapshot = json(impacts.stream().collect(Collectors.toMap(
                PlnInsertionReplanIssueEntity::getIssueId, i -> mesNow.get(i.getIssueId()).getExecutionFingerprint(),
                (a, b) -> a, LinkedHashMap::new)));
        require(Objects.equals(entity.getMesPublishedFingerprint(), hash(publishedSnapshot)),
                "MES执行事实已变化，禁止回退插单");
        for (PlnInsertionReplanIssueEntity impact : impacts)
            validateMesMovable(mesNow.get(impact.getIssueId()),
                    PlnFrozenRouteNodeIdentity.correspondence(current.get(impact.getIssueId()), mesNow.get(impact.getIssueId())).keySet());
        Map<Long, PlnIssueEntity> existingIssues = issues.entrySet().stream()
                .filter(e -> !Objects.equals(e.getKey(), insertion.getId()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        if (!rollbackRows.isEmpty())
            operationSchedulingService.validateInsertionReplan(existingIssues, rollbackRows, excluded);

        List<MesSync> synced = new ArrayList<>();
        try {
            for (PlnInsertionReplanIssueEntity impact : impacts) {
                if (Boolean.TRUE.equals(impact.getInsertionIssue())) continue;
                MesApsLocalScheduleDTO.StateBundle state = mesNow.get(impact.getIssueId());
                if (Boolean.TRUE.equals(state.getMesTaskPresent())) {
                    MesApsLocalScheduleDTO.ApplyReceipt applied = applyMes(requestId + ":MES:" + impact.getIssueId(),
                            entity.getId(), issues.get(impact.getIssueId()), state.getExecutionFingerprint(),
                            rollbackRows.get(impact.getIssueId()));
                    synced.add(new MesSync(issues.get(impact.getIssueId()), applied.getAfterExecutionFingerprint(),
                            current.get(impact.getIssueId())));
                    impact.setMesRollbackFingerprint(applied.getAfterExecutionFingerprint());
                } else impact.setMesRollbackFingerprint(state.getExecutionFingerprint());
            }
            for (PlnInsertionReplanIssueEntity impact : impacts) {
                if (Boolean.TRUE.equals(impact.getInsertionIssue())) continue;
                Map<String, PlnSchedulingOperationEntity> restore = uniqueByProcess(rollbackRows.get(impact.getIssueId()));
                List<PlnSchedulingOperationEntity> formal = current.get(impact.getIssueId());
                for (PlnSchedulingOperationEntity row : formal) {
                    copySchedule(restore.get(PlnFrozenRouteNodeIdentity.key(row)), row);
                    require(schedulingOperationMapper.updateById(row) == 1, "回退正式工序排程失败");
                }
                refreshFormalBatchSummaries(formal);
                updateFormalSummary(issues.get(impact.getIssueId()), formal, "紧急插单联合重排已受控回退");
            }
            removeInsertionFormalSchedule(entity, insertion);
            String rollbackSnapshot = json(impacts.stream().collect(Collectors.toMap(
                    PlnInsertionReplanIssueEntity::getIssueId, PlnInsertionReplanIssueEntity::getMesRollbackFingerprint,
                    (a, b) -> a, LinkedHashMap::new)));
            String rollbackFingerprint = hash(rollbackSnapshot);
            for (PlnInsertionReplanIssueEntity impact : impacts)
                require(impactMapper.updateById(impact) == 1,
                        "插单回退MES指纹保存失败");
            entity.setStatus("ROLLED_BACK").setRollbackRequestId(requestId).setRollbackReason(command.getReason().trim())
                    .setRolledBackBy(userCode(user)).setRolledBackByName(userName(user)).setRolledBackTime(LocalDateTime.now())
                    .setMesRollbackSnapshot(rollbackSnapshot).setMesRollbackFingerprint(rollbackFingerprint);
            require(caseMapper.updateById(entity) == 1, "插单回退状态保存失败");
            audit(entity, requestId, "ROLLBACK", "PUBLISHED", "ROLLED_BACK", command.getReason().trim());
            return receipt(entity, rollbackFingerprint);
        } catch (RuntimeException ex) {
            compensateMes(requestId + ":COMPENSATE", entity.getId(), synced, ex);
            throw ex;
        }
    }

    private void validateAssess(PlanInsertionLocalReplanDTO.AssessCommand command) {
        require(command != null && command.getInsertionIssueId() != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getUrgencyLevel()) && command.getRequestedStartTime() != null
                && StrUtil.isNotBlank(command.getBusinessReason()), "插单评估参数不完整");
        require(URGENCY_LEVELS.contains(command.getUrgencyLevel().trim().toUpperCase()),
                "紧急程度仅支持URGENT或EMERGENCY");
        require(command.getRequestId().trim().length() <= 48, "插单评估请求号不能超过48位");
        require(command.getBusinessReason().trim().length() <= 500, "插单业务原因不能超过500字");
    }

    private PlnIssueEntity requireInsertionIssue(PlnIssueEntity issue) {
        require(issue != null, "插单生产计划不存在");
        require(IssueStatusEnum.UNISSUED.getCode().equals(issue.getStatus()), "仅未下发生产计划允许作为紧急插单");
        require(!Boolean.TRUE.equals(issue.getLockStatus()) && issue.getLockTaskId() == null,
                "插单计划已经正式锁定排程");
        require(StrUtil.isNotBlank(issue.getProductCode()) && issue.getNum() != null
                        && issue.getNum().signum() > 0 && issue.getDemandTime() != null,
                "插单计划产品、数量和需求日期必须完整");
        return issue;
    }

    private PlnMaterialConstraintEvaluationDTO materialEvidence(PlnIssueEntity issue, LocalDate baseline) {
        PlnMaterialConstraintEvaluationDTO result = materialConstraintService.evaluate(List.of(issue), baseline).get(issue.getId());
        require(result != null, "插单物料约束评估未返回结果");
        return result;
    }

    private String materialSnapshot(PlnMaterialConstraintEvaluationDTO value) {
        Map<String, Object> stable = new LinkedHashMap<>();
        stable.put("issueId", value.getIssueId());
        stable.put("issueNo", value.getIssueNo());
        stable.put("status", value.getStatus());
        stable.put("hardBlocked", value.getHardBlocked());
        stable.put("materialAvailableDate", value.getMaterialAvailableDate());
        stable.put("materialAvailableTime", value.getMaterialAvailableTime());
        stable.put("summary", value.getSummary());
        stable.put("bomPathFingerprint", value.getBomPathFingerprint());
        stable.put("bomLineageVerified", value.getBomLineageVerified());
        stable.put("lines", value.getLines());
        stable.put("operationConstraints", value.getOperationConstraints());
        return json(stable);
    }

    private void applyMaterial(PlnIssueEntity issue, PlnMaterialConstraintEvaluationDTO value) {
        issue.setMaterialConstraintStatus(value.getStatus());
        issue.setMaterialAvailableDate(value.getMaterialAvailableDate());
        issue.setMaterialAvailableTime(value.getMaterialAvailableTime());
        issue.setMaterialHardBlocked(value.getHardBlocked());
        issue.setMaterialConstraintSummary(value.getSummary());
        issue.setMaterialConstraintSnapshot(json(value.getLines()));
        issue.setOperationMaterialConstraintSnapshot(json(value.getOperationConstraints()));
        issue.setMaterialLineageFingerprint(value.getBomPathFingerprint());
        issue.setMaterialConstraintCheckedTime(value.getCheckedTime());
    }

    private void ensureNoLiveProposal(Long issueId) {
        require(caseMapper.selectCount(new LambdaQueryWrapper<PlnInsertionReplanCaseEntity>()
                        .eq(PlnInsertionReplanCaseEntity::getInsertionIssueId, issueId)
                        .in(PlnInsertionReplanCaseEntity::getStatus, "PROPOSED", "SELECTED")) == 0,
                "计划已有待发布紧急插单候选");
        require(impactMapper.selectCount(new LambdaQueryWrapper<PlnInsertionReplanIssueEntity>()
                        .eq(PlnInsertionReplanIssueEntity::getIssueId, issueId)
                        .inSql(PlnInsertionReplanIssueEntity::getCaseId,
                                "SELECT id FROM pln_insertion_replan_case WHERE del_flag='0' AND status IN ('PROPOSED','SELECTED')")) == 0,
                "计划已被另一紧急插单候选占用");
        require(resourceCaseMapper.selectCount(new LambdaQueryWrapper<PlnResourceReplanCaseEntity>()
                        .eq(PlnResourceReplanCaseEntity::getIssueId, issueId)
                        .in(PlnResourceReplanCaseEntity::getStatus, "PROPOSED", "SELECTED")) == 0,
                "计划已有待发布资源异常重排候选");
        require(shortageCaseMapper.selectCount(new LambdaQueryWrapper<PlnShortageReplanCaseEntity>()
                        .eq(PlnShortageReplanCaseEntity::getIssueId, issueId)
                        .in(PlnShortageReplanCaseEntity::getStatus, "PROPOSED", "SELECTED")) == 0,
                "计划已有待发布缺料重排候选");
        require(qualityCaseMapper.selectCount(new LambdaQueryWrapper<PlnQualityReplanCaseEntity>()
                        .eq(PlnQualityReplanCaseEntity::getIssueId, issueId)
                        .in(PlnQualityReplanCaseEntity::getStatus, "PROPOSED", "SELECTED")) == 0,
                "计划已有待发布质量重排候选");
    }

    private Metrics metrics(PlnIssueEntity insertion, Set<Long> affectedIds, Map<Long, PlnIssueEntity> issues,
                            Map<Long, List<PlnSchedulingOperationEntity>> originals,
                            Map<Long, List<PlnSchedulingOperationEntity>> candidates) {
        int movedIssues = 0, movedOperations = candidates.get(insertion.getId()).size(), reassigned = 0, newLate = 0;
        long totalDelay = 0, maxDelay = 0;
        for (Long id : affectedIds) {
            PlnFrozenRouteNodeIdentity.replacementSet(originals.get(id), candidates.get(id));
            Map<String, PlnSchedulingOperationEntity> before = uniqueByProcess(originals.get(id));
            int changed = 0;
            for (PlnSchedulingOperationEntity after : candidates.get(id)) {
                PlnSchedulingOperationEntity original = before.get(PlnFrozenRouteNodeIdentity.key(after));
                if (!sameSchedule(original, after)) changed++;
                if (resourceChanged(original, after)) reassigned++;
            }
            if (changed > 0) movedIssues++;
            movedOperations += changed;
            long delay = Math.max(0, Duration.between(completion(originals.get(id)), completion(candidates.get(id))).toMinutes());
            totalDelay += delay;
            maxDelay = Math.max(maxDelay, delay);
            if (!"LATE".equals(deliveryRisk(issues.get(id), completion(originals.get(id))))
                    && "LATE".equals(deliveryRisk(issues.get(id), completion(candidates.get(id))))) newLate++;
        }
        LocalDateTime insertionCompletion = completion(candidates.get(insertion.getId()));
        long insertionLate = Math.max(0, Duration.between(insertion.getDemandTime().atTime(LocalTime.MAX), insertionCompletion).toMinutes());
        return new Metrics(movedIssues, movedOperations, reassigned, newLate, totalDelay, maxDelay,
                insertionLate, insertionCompletion);
    }

    private long insertionTotalShift(Set<Long> affectedIds,
                                     Map<Long, List<PlnSchedulingOperationEntity>> originals,
                                     Map<Long, List<PlnSchedulingOperationEntity>> candidates) {
        long total = 0;
        for (Long id : affectedIds) {
            Map<String, PlnSchedulingOperationEntity> before = uniqueByProcess(originals.get(id));
            for (PlnSchedulingOperationEntity after : candidates.get(id)) {
                PlnSchedulingOperationEntity original = before.get(PlnFrozenRouteNodeIdentity.key(after));
                if (original != null && original.getScheduledStartTime() != null && after.getScheduledStartTime() != null)
                    total += Math.abs(Duration.between(original.getScheduledStartTime(), after.getScheduledStartTime()).toMinutes());
            }
        }
        return total;
    }

    private BigDecimal insertionCandidateScore(Metrics metrics, long totalShift, long changeoverMinutes) {
        BigDecimal penalty = BigDecimal.valueOf(Math.min(35, metrics.insertionLate / 60.0 * 5)
                + Math.min(20, metrics.totalDelay / 60.0)
                + Math.min(15, totalShift / 120.0)
                + Math.min(15, metrics.reassignedOperations * 2.0)
                + Math.min(10, metrics.newLate * 5.0)
                + Math.min(5, changeoverMinutes / 30.0));
        return BigDecimal.valueOf(100).subtract(penalty).max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private void rankInsertionCandidates(List<InsertionCandidateBuild> builds) {
        List<InsertionCandidateBuild> ordered = builds.stream().sorted(Comparator
                .comparing((InsertionCandidateBuild item) -> !Boolean.TRUE.equals(item.task().getCandidateFeasible()))
                .thenComparing((InsertionCandidateBuild item) -> item.task().getCandidateScore(), Comparator.reverseOrder())
                .thenComparingLong(item -> item.metrics().insertionLate)
                .thenComparingLong(item -> item.metrics().totalDelay)
                .thenComparingLong(InsertionCandidateBuild::totalShift)
                .thenComparingLong(InsertionCandidateBuild::changeoverMinutes)
                .thenComparing(item -> item.task().getCandidateCode())).toList();
        for (int index = 0; index < ordered.size(); index++) {
            PlnSchedulingTaskEntity task = ordered.get(index).task();
            task.setCandidateRank(index + 1);
            require(taskMapper.updateById(task) == 1, "紧急插单候选排名保存失败");
        }
    }

    private void enrichCandidate(PlnInsertionReplanCaseEntity entity) {
        if (entity == null || entity.getScenarioId() == null || entity.getProposalTaskId() == null) return;
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
        if (header != null) entity.setScenarioNo(header.getScenarioNo());
    }

    private void saveImpactsAndLines(PlnInsertionReplanCaseEntity entity, PlnIssueEntity insertion,
                                     Set<Long> affectedIds, Map<Long, PlnIssueEntity> issues,
                                     Map<Long, List<PlnSchedulingOperationEntity>> originals,
                                     Map<Long, List<PlnSchedulingOperationEntity>> candidates,
                                     Map<Long, MesApsLocalScheduleDTO.StateBundle> mesBefore) {
        List<Long> ids = new ArrayList<>();
        ids.add(insertion.getId());
        ids.addAll(affectedIds.stream().sorted().toList());
        for (Long id : ids) {
            boolean inserted = Objects.equals(id, insertion.getId());
            PlnIssueEntity issue = issues.get(id);
            List<PlnSchedulingOperationEntity> before = originals.getOrDefault(id, List.of());
            List<PlnSchedulingOperationEntity> after = candidates.get(id);
            int changed = inserted ? after.size() : (int) after.stream().filter(a -> !sameSchedule(
                    uniqueByProcess(before).get(PlnFrozenRouteNodeIdentity.key(a)), a)).count();
            int reassigned = inserted ? after.size() : (int) after.stream().filter(a -> resourceChanged(
                    uniqueByProcess(before).get(PlnFrozenRouteNodeIdentity.key(a)), a)).count();
            long delay = inserted ? 0 : Math.max(0, Duration.between(completion(before), completion(after)).toMinutes());
            PlnInsertionReplanIssueEntity impact = new PlnInsertionReplanIssueEntity().setCaseId(entity.getId())
                    .setIssueId(id).setIssueNo(issue.getIssueNo()).setProductCode(issue.getProductCode())
                    .setProductName(issue.getProductName()).setInsertionIssue(inserted)
                    .setOriginalTaskId(inserted ? null : issue.getLockTaskId()).setDemandTime(issue.getDemandTime())
                    .setOriginalStartTime(inserted ? null : firstStart(before)).setOriginalEndTime(inserted ? null : completion(before))
                    .setProposedStartTime(firstStart(after)).setProposedEndTime(completion(after))
                    .setOriginalDeliveryRisk(inserted ? null : deliveryRisk(issue, completion(before)))
                    .setProposedDeliveryRisk(deliveryRisk(issue, completion(after))).setDelayMinutes(delay)
                    .setMovedOperationCount(changed).setReassignedOperationCount(reassigned)
                    .setOriginalIssueSnapshot(json(issue)).setOriginalScheduleSnapshot(inserted ? null : json(before))
                    .setOriginalScheduleFingerprint(inserted ? null : scheduleFingerprint(before))
                    .setProposedScheduleSnapshot(json(after)).setProposedScheduleFingerprint(scheduleStateFingerprint(after))
                    .setMesBeforeSnapshot(json(mesBefore.get(id))).setMesBeforeFingerprint(mesBefore.get(id).getExecutionFingerprint())
                    .setFactoryCode(entity.getFactoryCode());
            require(impactMapper.insert(impact) == 1, "插单逐计划影响保存失败");
            Map<String, PlnSchedulingOperationEntity> byCode = uniqueByProcess(before);
            for (PlnSchedulingOperationEntity candidate : after) {
                PlnSchedulingOperationEntity original = byCode.get(PlnFrozenRouteNodeIdentity.key(candidate));
                if (!inserted) PlnFrozenRouteNodeIdentity.sameIdentity(candidate, original);
                String action = inserted ? "INSERT" : (sameSchedule(original, candidate) ? "UNCHANGED" : "MOVE");
                long shift = inserted || original == null ? 0 : Math.abs(Duration.between(
                        original.getScheduledStartTime(), candidate.getScheduledStartTime()).toMinutes());
                PlnInsertionReplanLineEntity line = new PlnInsertionReplanLineEntity().setCaseId(entity.getId())
                        .setIssueImpactId(impact.getId()).setIssueId(id).setIssueNo(issue.getIssueNo())
                        .setInsertionIssue(inserted).setOriginalOperationId(original == null ? null : original.getId())
                        .setProposalOperationId(candidate.getId()).setOperationSeq(candidate.getOperationSeq())
                        .setSourceRouteNodeCode(candidate.getSourceRouteNodeCode()).setProcessCode(candidate.getProcessCode()).setProcessName(candidate.getProcessName()).setActionType(action)
                        .setOriginalStartTime(original == null ? null : original.getScheduledStartTime())
                        .setOriginalEndTime(original == null ? null : original.getScheduledEndTime())
                        .setProposedStartTime(candidate.getScheduledStartTime()).setProposedEndTime(candidate.getScheduledEndTime())
                        .setOriginalResourceCode(original == null ? null : original.getResourceCode())
                        .setProposedResourceCode(candidate.getResourceCode())
                        .setOriginalDeviceCode(original == null ? null : original.getDeviceCode()).setProposedDeviceCode(candidate.getDeviceCode())
                        .setOriginalEmployeeCode(original == null ? null : original.getEmployeeCode()).setProposedEmployeeCode(candidate.getEmployeeCode())
                        .setOriginalToolingCodes(original == null ? null : original.getToolingCodes()).setProposedToolingCodes(candidate.getToolingCodes())
                        .setResourceReassigned(inserted || resourceChanged(original, candidate)).setShiftMinutes(shift)
                        .setOriginalOperationSnapshot(original == null ? null : json(original))
                        .setProposedOperationSnapshot(json(candidate)).setFactoryCode(entity.getFactoryCode());
                require(lineMapper.insert(line) == 1, "插单逐工序影响保存失败");
            }
        }
    }

    private Map<Long, List<PlnSchedulingOperationEntity>> verifiedCandidates(PlnInsertionReplanCaseEntity entity,
                                                                             List<PlnInsertionReplanIssueEntity> impacts) {
        List<PlnInsertionReplanLineEntity> lines = caseLines(entity.getId());
        Set<Long> ids = lines.stream().map(PlnInsertionReplanLineEntity::getProposalOperationId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        List<PlnSchedulingOperationEntity> rows = schedulingOperationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>().in(PlnSchedulingOperationEntity::getId, ids)
                        .eq(PlnSchedulingOperationEntity::getTaskId, entity.getProposalTaskId()));
        require(rows.size() == ids.size(), "紧急插单候选工序已缺失");
        Map<Long, PlnInsertionReplanLineEntity> lineById = lines.stream().collect(Collectors.toMap(
                PlnInsertionReplanLineEntity::getProposalOperationId, Function.identity()));
        for (PlnSchedulingOperationEntity row : rows) {
            var line = lineById.get(row.getId());
            var frozen = readOperation(line.getProposedOperationSnapshot());
            PlnFrozenRouteNodeIdentity.sameIdentity(row, frozen);
            require(Objects.equals(line.getSourceRouteNodeCode(), row.getSourceRouteNodeCode())
                    && Objects.equals(line.getProcessCode(), row.getProcessCode()), "插单明细节点与候选身份不一致");
            if (!Boolean.TRUE.equals(line.getInsertionIssue())) {
                var original = readOperation(line.getOriginalOperationSnapshot());
                require(Objects.equals(original.getId(), line.getOriginalOperationId()), "插单明细与真实原工序主键不一致");
                PlnFrozenRouteNodeIdentity.sameIdentity(row, original);
            }
            require(Objects.equals(operationFingerprint(row), operationFingerprint(frozen)),
                    "插单候选工序[" + row.getIssueNo() + '/' + row.getProcessCode() + "]已被修改");
        }
        Map<Long, List<PlnSchedulingOperationEntity>> result = rows.stream().collect(Collectors.groupingBy(
                PlnSchedulingOperationEntity::getIssueId, LinkedHashMap::new, Collectors.toList()));
        require(result.keySet().containsAll(impacts.stream().map(PlnInsertionReplanIssueEntity::getIssueId).toList()),
                "插单候选计划范围不完整");
        return result;
    }

    private PlnSchedulingTaskEntity createOfficialTask(PlnInsertionReplanCaseEntity entity, PlnIssueEntity insertion,
                                                       List<PlnSchedulingOperationEntity> candidates) {
        PlnSchedulingTaskEntity official = new PlnSchedulingTaskEntity();
        PlnSchedulingTaskEntity proposal = taskMapper.selectById(entity.getProposalTaskId());
        require(proposal != null, "插单候选任务不存在");
        BeanUtils.copyProperties(proposal, official, "id", "createTime", "updateTime", "createBy", "updateBy");
        official.setId(null);
        official.setTaskNo("INS-" + IdUtil.getSnowflakeNextIdStr());
        official.setTriggerType("URGENT_INSERTION");
        official.setMode("official");
        official.setTaskStatus("SUCCESS");
        official.setPlanCount(1);
        official.setScenarioId(null);
        official.setCandidateCode(null);
        official.setCandidateName(null);
        official.setCandidateMetricsSnapshot(null);
        official.setCandidateScore(null);
        official.setCandidateRank(null);
        official.setCandidateFeasible(null);
        official.setSelectedCandidate(null);
        official.setRemark("紧急插单案件" + entity.getCaseNo() + "正式发布");
        require(taskMapper.insert(official) == 1, "插单正式排产任务保存失败");
        List<PlnSchedulingOperationEntity> formal = new ArrayList<>();
        for (PlnSchedulingOperationEntity candidate : candidates) {
            PlnSchedulingOperationEntity row = detachedOperation(candidate);
            row.setId(null);
            row.setTaskId(official.getId());
            row.setLockStatus(true);
            row.setLockTime(LocalDateTime.now());
            require(schedulingOperationMapper.insert(row) == 1, "插单正式工序排程保存失败");
            formal.add(row);
        }
        saveFormalDetail(official, insertion, formal);
        schedulingBatchService.initializeSingleBatches(official.getId(), List.of(insertion));
        schedulingBatchService.lockIssueBatches(official.getId(), insertion.getId(), LocalDateTime.now());
        return official;
    }

    private void lockInsertionIssue(PlnIssueEntity insertion, PlnSchedulingTaskEntity official,
                                    List<PlnSchedulingOperationEntity> candidates) {
        LocalDateTime start = firstStart(candidates), end = completion(candidates);
        insertion.setType("issue");
        insertion.setIssueTime(start.toLocalDate().atStartOfDay());
        insertion.setLockStatus(true);
        insertion.setLockTaskId(official.getId());
        insertion.setLockTime(LocalDateTime.now());
        insertion.setScheduledDate(start.toLocalDate());
        insertion.setScheduledEndDate(end.toLocalDate());
        require(issueMapper.updateById(insertion) == 1, "插单计划正式锁定失败");
    }

    private void removeInsertionFormalSchedule(PlnInsertionReplanCaseEntity entity, PlnIssueEntity insertion) {
        require(Objects.equals(insertion.getLockTaskId(), entity.getPublishedTaskId()), "插单正式任务已变化，禁止回退");
        PlnInsertionReplanIssueEntity impact = impactMapper.selectOne(new LambdaQueryWrapper<PlnInsertionReplanIssueEntity>()
                .eq(PlnInsertionReplanIssueEntity::getCaseId, entity.getId())
                .eq(PlnInsertionReplanIssueEntity::getInsertionIssue, true));
        PlnIssueEntity original = readIssue(impact.getOriginalIssueSnapshot());
        schedulingOperationMapper.delete(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getTaskId, entity.getPublishedTaskId()));
        schedulingBatchAllocationMapper.delete(new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                .eq(PlnSchedulingBatchAllocationEntity::getTaskId, entity.getPublishedTaskId()));
        schedulingBatchMapper.delete(new LambdaQueryWrapper<PlnSchedulingBatchEntity>()
                .eq(PlnSchedulingBatchEntity::getTaskId, entity.getPublishedTaskId()));
        taskDetailMapper.delete(new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                .eq(PlnSchedulingTaskDetailEntity::getTaskId, entity.getPublishedTaskId()));
        taskMapper.deleteById(entity.getPublishedTaskId());
        insertion.setLockStatus(original.getLockStatus());
        insertion.setLockTaskId(original.getLockTaskId());
        insertion.setLockTime(original.getLockTime());
        insertion.setScheduledDate(original.getScheduledDate());
        insertion.setScheduledEndDate(original.getScheduledEndDate());
        insertion.setIssueTime(original.getIssueTime());
        insertion.setType(original.getType());
        insertion.setPlanType(original.getPlanType());
        require(issueMapper.updateById(insertion) == 1, "插单计划解锁回退失败");
    }

    private void saveProposalDetail(PlnSchedulingTaskEntity task, PlnIssueEntity issue,
                                    List<PlnSchedulingOperationEntity> operations) {
        PlnSchedulingTaskDetailEntity detail = baseDetail(task, issue, operations);
        detail.setLockStatus(false);
        detail.setLockTaskId(issue.getLockTaskId());
        detail.setStatus("PROPOSED");
        require(taskDetailMapper.insert(detail) == 1, "插单候选任务明细保存失败");
    }

    private void saveFormalDetail(PlnSchedulingTaskEntity task, PlnIssueEntity issue,
                                  List<PlnSchedulingOperationEntity> operations) {
        PlnSchedulingTaskDetailEntity detail = baseDetail(task, issue, operations);
        detail.setLockStatus(true);
        detail.setLockTaskId(task.getId());
        detail.setLockTime(LocalDateTime.now());
        detail.setStatus("LOCKED");
        require(taskDetailMapper.insert(detail) == 1, "插单正式任务明细保存失败");
    }

    private PlnSchedulingTaskDetailEntity baseDetail(PlnSchedulingTaskEntity task, PlnIssueEntity issue,
                                                     List<PlnSchedulingOperationEntity> operations) {
        PlnSchedulingTaskDetailEntity detail = new PlnSchedulingTaskDetailEntity();
        BeanUtils.copyProperties(issue, detail, "id", "createTime", "updateTime", "createBy", "updateBy");
        detail.setId(null);
        detail.setTaskId(task.getId());
        detail.setIssueId(issue.getId());
        detail.setOperationCount(operations.size());
        detail.setOperationStartTime(firstStart(operations));
        detail.setOperationEndTime(completion(operations));
        detail.setScheduledDate(firstStart(operations).toLocalDate());
        detail.setScheduledEndDate(completion(operations).toLocalDate());
        detail.setOperationScheduleStatus("SCHEDULED");
        detail.setOperationHardBlocked(false);
        detail.setOperationScheduleSummary("紧急插单工序级有限产能排程");
        detail.setProcessRouteCode(operations.stream().map(PlnSchedulingOperationEntity::getRouteCode)
                .filter(StrUtil::isNotBlank).findFirst().orElse(null));
        return detail;
    }

    private void updateFormalSummary(PlnIssueEntity issue, List<PlnSchedulingOperationEntity> rows, String summary) {
        LocalDateTime start = firstStart(rows), end = completion(rows);
        issue.setScheduledDate(start.toLocalDate());
        issue.setScheduledEndDate(end.toLocalDate());
        require(issueMapper.updateById(issue) == 1, "生产计划排程日期更新失败");
        PlnSchedulingTaskDetailEntity detail = taskDetailMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>().eq(PlnSchedulingTaskDetailEntity::getTaskId, issue.getLockTaskId())
                        .eq(PlnSchedulingTaskDetailEntity::getIssueId, issue.getId()).last("LIMIT 1"));
        if (detail != null) {
            detail.setScheduledDate(start.toLocalDate());
            detail.setScheduledEndDate(end.toLocalDate());
            detail.setOperationStartTime(start);
            detail.setOperationEndTime(end);
            detail.setOperationCount(rows.size());
            detail.setOperationScheduleStatus("SCHEDULED");
            detail.setOperationHardBlocked(false);
            detail.setOperationScheduleSummary(summary);
            require(taskDetailMapper.updateById(detail) == 1, "正式排程任务明细更新失败");
        }
    }

    private Map<Long, MesApsLocalScheduleDTO.StateBundle> captureMes(Map<Long, PlnIssueEntity> issues) {
        Map<Long, MesApsLocalScheduleDTO.StateBundle> result = new LinkedHashMap<>();
        for (Map.Entry<Long, PlnIssueEntity> entry : issues.entrySet())
            result.put(entry.getKey(), mesStates(entry.getValue()));
        return result;
    }

    private MesApsLocalScheduleDTO.StateBundle mesStates(PlnIssueEntity issue) {
        R<MesApsLocalScheduleDTO.StateBundle> response = mesTaskService.apsOperationStates(issue.getIssueNo(), issue.getFactoryCode());
        require(response != null && response.isOk() && response.getData() != null
                        && StrUtil.isNotBlank(response.getData().getExecutionFingerprint()),
                "计划[" + issue.getIssueNo() + "]的MES执行事实不可用");
        return response.getData();
    }

    private void validateMesMovable(MesApsLocalScheduleDTO.StateBundle bundle, Collection<String> processCodes) {
        if (!Boolean.TRUE.equals(bundle.getMesTaskPresent())) return;
        Map<String, MesApsLocalScheduleDTO.OperationState> byCode = PlnFrozenRouteNodeIdentity.states(bundle);
        for (String code : processCodes) {
            MesApsLocalScheduleDTO.OperationState state = PlnFrozenRouteNodeIdentity.state(byCode, code);
            require(state != null, "MES工艺快照缺少工序[" + code + "]");
            require(Boolean.TRUE.equals(state.getMovable()), "MES工序[" + code + "]不可移动：" + state.getBlockedReason());
        }
    }

    private MesApsLocalScheduleDTO.ApplyReceipt applyMes(String requestId, Long caseId, PlnIssueEntity issue,
                                                         String expectedFingerprint, List<PlnSchedulingOperationEntity> operations) {
        MesApsLocalScheduleDTO.ApplyCommand command = new MesApsLocalScheduleDTO.ApplyCommand().setRequestId(requestId)
                .setPlanNo(issue.getIssueNo()).setFactoryCode(issue.getFactoryCode()).setExpectedExecutionFingerprint(expectedFingerprint)
                .setReplanCaseId(caseId).setOperations(operations.stream().map(this::toMesUpdate).toList());
        R<MesApsLocalScheduleDTO.ApplyReceipt> response = mesTaskService.applyApsLocalSchedule(command);
        require(response != null && response.isOk() && response.getData() != null,
                "MES未确认计划[" + issue.getIssueNo() + "]插单排程同步成功");
        return response.getData();
    }

    private MesApsLocalScheduleDTO.OperationUpdate toMesUpdate(PlnSchedulingOperationEntity item) {
        return new MesApsLocalScheduleDTO.OperationUpdate().setSourceRouteNodeCode(item.getSourceRouteNodeCode()).setRouteCode(item.getProcessCode())
                .setPlannedStartTime(item.getScheduledStartTime()).setPlannedEndTime(item.getScheduledEndTime())
                .setResourceCode(item.getResourceCode()).setResourceName(item.getResourceName())
                .setResourceSlotNo(item.getResourceSlotNo()).setResourceGroupCode(item.getResourceGroupCode())
                .setResourceGroupName(item.getResourceGroupName()).setResourceGroupCapacity(item.getResourceGroupCapacity())
                .setResourceGroupSlotNo(item.getResourceGroupSlotNo()).setDeviceCode(item.getDeviceCode())
                .setDeviceName(item.getDeviceName()).setEmployeeCode(item.getEmployeeCode()).setEmployeeName(item.getEmployeeName())
                .setToolingCodes(item.getToolingCodes()).setToolingNames(item.getToolingNames())
                .setToolingAllocationSnapshot(item.getToolingAllocationSnapshot())
                .setToolingLifeUsageSnapshot(item.getToolingLifeUsageSnapshot()).setApsOperationSnapshot(json(item));
    }

    private void compensateMes(String prefix, Long caseId, List<MesSync> synced, RuntimeException originalFailure) {
        ListIterator<MesSync> iterator = synced.listIterator(synced.size());
        try {
            while (iterator.hasPrevious()) {
                MesSync sync = iterator.previous();
                if (!sync.rollbackRows.isEmpty()) applyMes(prefix + ':' + sync.issue.getId(), caseId, sync.issue,
                        sync.afterFingerprint, sync.rollbackRows);
            }
        } catch (RuntimeException compensationFailure) {
            throw new CheckedException("PLAN事务将回滚但MES多计划补偿失败，需要人工对账；原错误："
                    + originalFailure.getMessage() + "；补偿错误：" + compensationFailure.getMessage());
        }
    }

    private PlnInsertionReplanPolicyEntity resolvePolicy(String factoryCode, LocalDateTime time) {
        return policyMapper.selectList(new LambdaQueryWrapper<PlnInsertionReplanPolicyEntity>()
                        .eq(PlnInsertionReplanPolicyEntity::getFactoryCode, factoryCode)
                        .eq(PlnInsertionReplanPolicyEntity::getStatus, "ACTIVE")
                        .and(w -> w.isNull(PlnInsertionReplanPolicyEntity::getEffectiveFrom)
                                .or().le(PlnInsertionReplanPolicyEntity::getEffectiveFrom, time))
                        .and(w -> w.isNull(PlnInsertionReplanPolicyEntity::getEffectiveTo)
                                .or().gt(PlnInsertionReplanPolicyEntity::getEffectiveTo, time))
                        .orderByAsc(PlnInsertionReplanPolicyEntity::getPriority)
                        .orderByDesc(PlnInsertionReplanPolicyEntity::getPolicyVersion))
                .stream().findFirst().orElse(null);
    }

    private String policyFingerprint(PlnInsertionReplanPolicyEntity e) {
        return hash(String.join("|", e.getFactoryCode(), e.getPolicyCode(), Objects.toString(e.getPolicyVersion(), ""),
                Objects.toString(e.getFreezeMinutes(), ""), Objects.toString(e.getImpactHorizonDays(), ""),
                Objects.toString(e.getMaxAffectedIssues(), ""), Objects.toString(e.getMaxMovedOperations(), ""),
                Objects.toString(e.getMaxTotalDelayMinutes(), ""), Objects.toString(e.getMaxSingleIssueDelayMinutes(), ""),
                Objects.toString(e.getMaxInsertionLateMinutes(), ""), Objects.toString(e.getMaxNewLateIssues(), ""),
                Objects.toString(e.getPriority(), ""), Objects.toString(e.getEffectiveFrom(), ""),
                Objects.toString(e.getEffectiveTo(), "")));
    }

    private PlnInsertionReplanPolicyEntity requirePolicy(Long id, boolean lock) {
        LambdaQueryWrapper<PlnInsertionReplanPolicyEntity> q = new LambdaQueryWrapper<PlnInsertionReplanPolicyEntity>()
                .eq(PlnInsertionReplanPolicyEntity::getId, id);
        if (lock) q.last("FOR UPDATE");
        PlnInsertionReplanPolicyEntity result = policyMapper.selectOne(q);
        require(result != null, "紧急插单策略不存在");
        requireFactory(result.getFactoryCode());
        return result;
    }

    private PlnInsertionReplanCaseEntity requireCase(Long id, boolean lock) {
        LambdaQueryWrapper<PlnInsertionReplanCaseEntity> q = new LambdaQueryWrapper<PlnInsertionReplanCaseEntity>()
                .eq(PlnInsertionReplanCaseEntity::getId, id);
        if (lock) q.last("FOR UPDATE");
        PlnInsertionReplanCaseEntity result = caseMapper.selectOne(q);
        require(result != null, "紧急插单案件不存在");
        requireFactory(result.getFactoryCode());
        return result;
    }

    private PlnIssueEntity lockIssue(Long id) {
        PlnIssueEntity result = issueMapper.selectOne(new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getId, id).last("FOR UPDATE"));
        require(result != null, "生产计划不存在");
        requireFactory(result.getFactoryCode());
        return result;
    }

    private List<PlnSchedulingOperationEntity> lockedOperations(PlnIssueEntity issue) {
        require(Boolean.TRUE.equals(issue.getLockStatus()) && issue.getLockTaskId() != null,
                "计划[" + issue.getIssueNo() + "]尚未形成正式锁定排程");
        require(!Set.of(IssueStatusEnum.COMPLETE.getCode(), IssueStatusEnum.NOT_ISSUED.getCode()).contains(issue.getStatus()),
                "计划[" + issue.getIssueNo() + "]已完成或关闭");
        List<PlnSchedulingOperationEntity> rows = schedulingOperationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>().eq(PlnSchedulingOperationEntity::getTaskId, issue.getLockTaskId())
                        .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId()).eq(PlnSchedulingOperationEntity::getLockStatus, true)
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq).last("FOR UPDATE"));
        require(!rows.isEmpty() && rows.stream().noneMatch(item -> Boolean.TRUE.equals(item.getHardBlocked())),
                "计划[" + issue.getIssueNo() + "]正式工序排程不存在或存在硬阻断");
        require(rows.stream().map(PlnSchedulingOperationEntity::getSchedulingBatchType)
                        .filter(StrUtil::isNotBlank).allMatch("SINGLE"::equalsIgnoreCase),
                "计划[" + issue.getIssueNo() + "]属于拆批或合批执行账，不能拆散来源计划插单；请在批次排产工作台生成批次级情景");
        return rows;
    }

    private void refreshFormalBatchSummaries(List<PlnSchedulingOperationEntity> operations) {
        Map<Long, List<PlnSchedulingOperationEntity>> byBatch = operations.stream()
                .filter(item -> item.getSchedulingBatchId() != null)
                .collect(Collectors.groupingBy(PlnSchedulingOperationEntity::getSchedulingBatchId));
        for (Map.Entry<Long, List<PlnSchedulingOperationEntity>> entry : byBatch.entrySet()) {
            PlnSchedulingBatchEntity batch = schedulingBatchMapper.selectOne(
                    new LambdaQueryWrapper<PlnSchedulingBatchEntity>()
                            .eq(PlnSchedulingBatchEntity::getId, entry.getKey()).last("FOR UPDATE"));
            require(batch != null, "正式排程批次账不存在，禁止形成工序与数量账不一致");
            batch.setScheduledStartTime(firstStart(entry.getValue()));
            batch.setScheduledEndTime(completion(entry.getValue()));
            require(schedulingBatchMapper.updateById(batch) == 1, "正式排程批次时间汇总更新失败");
        }
    }

    private List<PlnInsertionReplanIssueEntity> caseImpacts(Long caseId) {
        return impactMapper.selectList(new LambdaQueryWrapper<PlnInsertionReplanIssueEntity>()
                .eq(PlnInsertionReplanIssueEntity::getCaseId, caseId)
                .orderByDesc(PlnInsertionReplanIssueEntity::getInsertionIssue)
                .orderByAsc(PlnInsertionReplanIssueEntity::getOriginalStartTime));
    }

    private List<PlnInsertionReplanLineEntity> caseLines(Long caseId) {
        return lineMapper.selectList(new LambdaQueryWrapper<PlnInsertionReplanLineEntity>()
                .eq(PlnInsertionReplanLineEntity::getCaseId, caseId)
                .orderByAsc(PlnInsertionReplanLineEntity::getIssueId)
                .orderByAsc(PlnInsertionReplanLineEntity::getOperationSeq));
    }

    private List<PlnSchedulingOperationEntity> proposalOperations(Long taskId, Set<Long> issueIds) {
        return schedulingOperationMapper.selectList(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getTaskId, taskId).in(PlnSchedulingOperationEntity::getIssueId, issueIds)
                .orderByAsc(PlnSchedulingOperationEntity::getIssueId).orderByAsc(PlnSchedulingOperationEntity::getOperationSeq));
    }

    private boolean sharesResource(PlnSchedulingOperationEntity a, PlnSchedulingOperationEntity b) {
        if (sameNonBlank(a.getResourceCode(), b.getResourceCode())
                && Objects.equals(a.getResourceSlotNo(), b.getResourceSlotNo())) return true;
        if (sameNonBlank(a.getResourceGroupCode(), b.getResourceGroupCode())
                && Objects.equals(a.getResourceGroupSlotNo(), b.getResourceGroupSlotNo())) return true;
        if (sameNonBlank(a.getDeviceCode(), b.getDeviceCode())) return true;
        if (!Collections.disjoint(splitCodes(a.getEmployeeCode()), splitCodes(b.getEmployeeCode()))) return true;
        return !Collections.disjoint(splitCodes(a.getToolingCodes()), splitCodes(b.getToolingCodes()));
    }

    /**
     * 探测排程因正式占用会被推后；将其整体平移到请求时刻，圈定真正会被插单挤占的资源窗口，
     * 避免把整个策略周期内使用过同一资源的计划都无差别释放。
     */
    private boolean conflictsWithShiftedProbe(PlnSchedulingOperationEntity locked,
                                              List<PlnSchedulingOperationEntity> probe,
                                              LocalDateTime requestedStart) {
        LocalDateTime probeStart = firstStart(probe);
        long shiftMinutes = Duration.between(probeStart, requestedStart).toMinutes();
        return probe.stream().filter(item -> sharesResource(item, locked))
                .anyMatch(item -> overlaps(item.getScheduledStartTime().plusMinutes(shiftMinutes),
                        item.getScheduledEndTime().plusMinutes(shiftMinutes),
                        locked.getScheduledStartTime(), locked.getScheduledEndTime()));
    }

    private boolean sameNonBlank(String a, String b) {
        return StrUtil.isNotBlank(a) && Objects.equals(a, b);
    }

    private boolean overlaps(LocalDateTime aStart, LocalDateTime aEnd, LocalDateTime bStart, LocalDateTime bEnd) {
        return aStart != null && aEnd != null && bStart != null && bEnd != null
                && aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    private boolean resourceChanged(PlnSchedulingOperationEntity a, PlnSchedulingOperationEntity b) {
        if (a == null || b == null) return true;
        return !Objects.equals(a.getResourceCode(), b.getResourceCode())
                || !Objects.equals(a.getResourceSlotNo(), b.getResourceSlotNo())
                || !Objects.equals(a.getResourceGroupCode(), b.getResourceGroupCode())
                || !Objects.equals(a.getResourceGroupSlotNo(), b.getResourceGroupSlotNo())
                || !Objects.equals(a.getDeviceCode(), b.getDeviceCode())
                || !Objects.equals(a.getEmployeeCode(), b.getEmployeeCode())
                || !Objects.equals(a.getToolingCodes(), b.getToolingCodes());
    }

    private boolean sameSchedule(PlnSchedulingOperationEntity a, PlnSchedulingOperationEntity b) {
        return a != null && b != null && Objects.equals(a.getScheduledStartTime(), b.getScheduledStartTime())
                && Objects.equals(a.getScheduledEndTime(), b.getScheduledEndTime()) && !resourceChanged(a, b);
    }

    private Map<String, PlnSchedulingOperationEntity> uniqueByProcess(List<PlnSchedulingOperationEntity> rows) {
        return PlnFrozenRouteNodeIdentity.index(rows);
    }

    private String aggregateMesFingerprint(Map<Long, MesApsLocalScheduleDTO.StateBundle> states) {
        return hash(states.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue().getExecutionFingerprint()).collect(Collectors.joining("||")));
    }

    private String aggregateScheduleFingerprint(Map<Long, List<PlnSchedulingOperationEntity>> rows) {
        return hash(rows.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + scheduleFingerprint(e.getValue())).collect(Collectors.joining("||")));
    }

    private String scheduleFingerprint(List<PlnSchedulingOperationEntity> rows) {
        return hash(rows.stream().sorted(Comparator.comparing(PlnSchedulingOperationEntity::getOperationSeq,
                Comparator.nullsLast(Integer::compareTo)).thenComparing(PlnSchedulingOperationEntity::getId,
                Comparator.nullsLast(Long::compareTo))).map(this::operationFingerprint).collect(Collectors.joining("||")));
    }

    /**
     * 发布会生成新的正式任务和主键，回退校验只比较业务排程，不比较载体身份。
     */
    private String scheduleStateFingerprint(List<PlnSchedulingOperationEntity> rows) {
        return hash(rows.stream().sorted(Comparator.comparing(PlnSchedulingOperationEntity::getOperationSeq,
                        Comparator.nullsLast(Integer::compareTo)))
                .map(item -> String.join("|", Objects.toString(item.getOperationSeq(), ""),
                        Objects.toString(item.getRouteCode(), ""), Objects.toString(item.getProcessCode(), ""),
                        Objects.toString(item.getResourceType(), ""), Objects.toString(item.getResourceCode(), ""),
                        Objects.toString(item.getResourceSlotNo(), ""), Objects.toString(item.getResourceGroupCode(), ""),
                        Objects.toString(item.getResourceGroupSlotNo(), ""), Objects.toString(item.getDeviceCode(), ""),
                        Objects.toString(item.getEmployeeCode(), ""), Objects.toString(item.getToolingCodes(), ""),
                        Objects.toString(item.getScheduledStartTime(), ""), Objects.toString(item.getScheduledEndTime(), ""),
                        Objects.toString(item.getTransferReadyTime(), "")))
                .collect(Collectors.joining("||")));
    }

    private String operationFingerprint(PlnSchedulingOperationEntity item) {
        return String.join("|", Objects.toString(item.getId(), ""), Objects.toString(item.getTaskId(), ""),
                Objects.toString(item.getIssueId(), ""), Objects.toString(item.getOperationSeq(), ""),
                Objects.toString(item.getRouteCode(), ""), Objects.toString(item.getProcessCode(), ""),
                Objects.toString(item.getPredecessorCodes(), ""), Objects.toString(item.getResourceType(), ""),
                Objects.toString(item.getResourceCode(), ""), Objects.toString(item.getResourceSlotNo(), ""),
                Objects.toString(item.getResourceGroupCode(), ""), Objects.toString(item.getResourceGroupSlotNo(), ""),
                Objects.toString(item.getDeviceCode(), ""), Objects.toString(item.getEmployeeCode(), ""),
                Objects.toString(item.getToolingCodes(), ""), Objects.toString(item.getScheduledStartTime(), ""),
                Objects.toString(item.getScheduledEndTime(), ""), Objects.toString(item.getTransferReadyTime(), ""),
                Objects.toString(item.getScheduleStatus(), ""), Objects.toString(item.getHardBlocked(), ""),
                Objects.toString(item.getLockStatus(), ""));
    }

    private String issueFingerprint(PlnIssueEntity i) {
        return hash(String.join("|", Objects.toString(i.getId(), ""), Objects.toString(i.getIssueNo(), ""),
                Objects.toString(i.getProductCode(), ""), Objects.toString(i.getNum(), ""),
                Objects.toString(i.getDemandTime(), ""), Objects.toString(i.getPlanType(), ""),
                Objects.toString(i.getStatus(), ""), Objects.toString(i.getLockStatus(), ""),
                Objects.toString(i.getLockTaskId(), ""), Objects.toString(i.getFactoryCode(), "")));
    }

    private void copySchedule(PlnSchedulingOperationEntity source, PlnSchedulingOperationEntity target) {
        PlnFrozenRouteNodeIdentity.sameIdentity(source, target);
        require(source != null && target != null, "候选与正式工序对应关系缺失");
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
        target.setScheduledStartTime(source.getScheduledStartTime());
        target.setScheduledEndTime(source.getScheduledEndTime());
        target.setTransferReadyTime(source.getTransferReadyTime());
        target.setScheduleStatus(source.getScheduleStatus());
        target.setHardBlocked(source.getHardBlocked());
        target.setConstraintSummary(source.getConstraintSummary());
        target.setSourceSnapshot(source.getSourceSnapshot());
    }

    private PlnIssueEntity detachedIssue(PlnIssueEntity source) {
        PlnIssueEntity r = new PlnIssueEntity();
        BeanUtils.copyProperties(source, r);
        return r;
    }

    private PlnSchedulingOperationEntity detachedOperation(PlnSchedulingOperationEntity source) {
        PlnSchedulingOperationEntity r = new PlnSchedulingOperationEntity();
        BeanUtils.copyProperties(source, r);
        return r;
    }

    private LocalDateTime firstStart(List<PlnSchedulingOperationEntity> rows) {
        return rows.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime).filter(Objects::nonNull).min(LocalDateTime::compareTo).orElseThrow();
    }

    private LocalDateTime completion(List<PlnSchedulingOperationEntity> rows) {
        return rows.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime).filter(Objects::nonNull).max(LocalDateTime::compareTo).orElseThrow();
    }

    private String deliveryRisk(PlnIssueEntity issue, LocalDateTime completion) {
        if (issue.getDemandTime() == null) return "AT_RISK";
        LocalDateTime deadline = issue.getDemandTime().atTime(LocalTime.MAX);
        if (completion.isAfter(deadline)) return "LATE";
        return Duration.between(completion, deadline).toHours() < 24 ? "AT_RISK" : "ON_TIME";
    }

    private PlnSchedulingOperationEntity readOperation(String json) {
        try {
            return objectMapper.readValue(json, PlnSchedulingOperationEntity.class);
        } catch (Exception e) {
            throw new CheckedException("插单工序快照损坏");
        }
    }

    private List<PlnSchedulingOperationEntity> readOperations(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, PlnSchedulingOperationEntity.class));
        } catch (Exception e) {
            throw new CheckedException("插单原排程快照损坏");
        }
    }

    private PlnIssueEntity readIssue(String json) {
        try {
            return objectMapper.readValue(json, PlnIssueEntity.class);
        } catch (Exception e) {
            throw new CheckedException("插单计划快照损坏");
        }
    }

    private void markStale(PlnInsertionReplanCaseEntity entity, String requestId, MomUser user, String reason) {
        List<PlnInsertionReplanCaseEntity> cases = entity.getScenarioId() == null ? List.of(entity)
                : caseMapper.selectList(new LambdaQueryWrapper<PlnInsertionReplanCaseEntity>()
                .eq(PlnInsertionReplanCaseEntity::getScenarioId, entity.getScenarioId()).last("FOR UPDATE"));
        for (PlnInsertionReplanCaseEntity item : cases) {
            String before = item.getStatus();
            item.setStatus("STALE");
            if (Objects.equals(item.getId(), entity.getId())) item.setPublishRequestId(requestId)
                    .setPublisherCode(userCode(user)).setPublisherName(userName(user)).setPublishedTime(LocalDateTime.now());
            require(caseMapper.updateById(item) == 1, "插单候选失效状态保存失败");
            audit(item, requestId + (Objects.equals(item.getId(), entity.getId()) ? "" : ":" + item.getId()),
                    "EVIDENCE_STALE", before, "STALE", reason);
        }
        if (entity.getScenarioId() != null) {
            PlnSchedulingScenarioEntity header = scenarioMapper.selectById(entity.getScenarioId());
            if (header != null && !Set.of("PUBLISHED", "FAILED").contains(header.getScenarioStatus())) {
                header.setScenarioStatus("FAILED");
                require(scenarioMapper.updateById(header) == 1, "插单情景失效状态保存失败");
            }
        }
    }

    private PlanInsertionLocalReplanDTO.PublishReceipt receipt(PlnInsertionReplanCaseEntity e, String mesFingerprint) {
        return new PlanInsertionLocalReplanDTO.PublishReceipt().setCaseId(e.getId()).setCaseNo(e.getCaseNo())
                .setInsertionIssueNo(e.getInsertionIssueNo()).setStatus(e.getStatus()).setPublishedTaskId(e.getPublishedTaskId())
                .setAffectedIssueCount(e.getAffectedIssueCount()).setMovedOperationCount(e.getMovedOperationCount())
                .setNewLateIssueCount(e.getNewLateIssueCount()).setInsertionCompletionTime(e.getInsertionCompletionTime())
                .setMesAfterExecutionFingerprint(mesFingerprint);
    }

    private void audit(PlnInsertionReplanCaseEntity e, String requestId, String type,
                       String before, String after, String content) {
        MomUser user = currentUser();
        require(auditMapper.insert(new PlnInsertionReplanOperationEntity().setCaseId(e.getId())
                .setRequestId(requestId).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                .setContent(content).setOperatorCode(userCode(user)).setOperatorName(userName(user))
                .setOperationTime(LocalDateTime.now()).setFactoryCode(e.getFactoryCode())) == 1, "插单操作履历保存失败");
    }

    private boolean intervalsOverlap(LocalDateTime aStart, LocalDateTime aEnd, LocalDateTime bStart, LocalDateTime bEnd) {
        return (aEnd == null || bStart.isBefore(aEnd)) && (bEnd == null || aStart.isBefore(bEnd));
    }

    private boolean isEffective(PlnInsertionReplanPolicyEntity p, LocalDateTime t) {
        return (p.getEffectiveFrom() == null || !p.getEffectiveFrom().isAfter(t)) && (p.getEffectiveTo() == null || p.getEffectiveTo().isAfter(t));
    }

    private int bounded(Integer value, int defaultValue, int min, int max, String message) {
        int r = value == null ? defaultValue : value;
        require(r >= min && r <= max, message);
        return r;
    }

    private LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private List<String> splitCodes(String value) {
        return StrUtil.isBlank(value) ? List.of() : Arrays.stream(value.split(",")).map(String::trim).filter(StrUtil::isNotBlank).distinct().toList();
    }

    private MomUser currentUser() {
        try {
            return SecurityUtils.getUser();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String userCode(MomUser u) {
        return u == null ? "SYSTEM" : u.getUsername();
    }

    private String userName(MomUser u) {
        return u == null ? "系统" : StrUtil.blankToDefault(u.getName(), u.getUsername());
    }

    private void requireFactory(String factoryCode) {
        String current = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(current) || Objects.equals(current, factoryCode), "请求工厂超出当前数据范围");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new CheckedException("插单证据序列化失败");
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new CheckedException("插单证据指纹生成失败");
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    private record Metrics(int movedIssues, int movedOperations, int reassignedOperations, int newLate,
                           long totalDelay, long maxDelay, long insertionLate, LocalDateTime insertionCompletion) {
    }

    private record InsertionCandidateProfile(String code, String name, String solverStrategy,
                                             String sequenceStrategy) {
    }

    private record InsertionCandidateBuild(PlnInsertionReplanCaseEntity entity, PlnSchedulingTaskEntity task,
                                           Metrics metrics, long totalShift, long changeoverMinutes) {
    }

    private record MesSync(PlnIssueEntity issue, String afterFingerprint,
                           List<PlnSchedulingOperationEntity> rollbackRows) {
    }
}
