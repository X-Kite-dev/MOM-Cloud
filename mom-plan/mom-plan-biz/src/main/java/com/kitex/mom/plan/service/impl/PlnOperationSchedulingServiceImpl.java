package com.kitex.mom.plan.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.constant.CommonConstants;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.*;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.feign.*;
import com.kitex.mom.mdm.vo.MdmProcessProcedureWorkVO;
import com.kitex.mom.mdm.vo.MdmProcessRouteWorkingVO;
import com.kitex.mom.plan.constant.SchedulingOperationStatusEnum;
import com.kitex.mom.plan.dto.PlnOperationMaterialConstraintDTO;
import com.kitex.mom.plan.dto.PlnOperationScheduleEvaluationDTO;
import com.kitex.mom.plan.dto.PlnSchedulingBatchLedgerDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlnOperationSchedulingService;
import com.kitex.mom.tpm.dto.TpmDeviceAvailabilityQueryDTO;
import com.kitex.mom.tpm.dto.TpmDeviceUnavailableWindowDTO;
import com.kitex.mom.tpm.feign.RemoteTpmDeviceAvailabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlnOperationSchedulingServiceImpl implements PlnOperationSchedulingService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final LocalTime DEFAULT_SHIFT_START = LocalTime.of(8, 0);
    private static final LocalTime DEFAULT_SHIFT_END = LocalTime.of(16, 0);
    private static final int MAX_CALENDAR_DAYS = 730;
    private static final int DEVICE_QUERY_BATCH_SIZE = 500;
    private static final int WORKFORCE_QUERY_BATCH_SIZE = 200;
    private static final int MAX_WORKFORCE_COMBINATIONS = 512;
    private static final int CHANGEOVER_LOOKBACK_DAYS = 365;

    private final PlnSchedulingOperationMapper operationMapper;
    private final com.kitex.mom.plan.service.solver.SequenceSolverRegistry sequenceSolvers;
    private final PlnSchedulingTaskDetailMapper taskDetailMapper;
    private final com.kitex.mom.plan.mapper.PlnSchedulingTaskMapper lifecycleTaskMapper;
    private final PlnIssueMapper issueMapper;
    private final PlnSchedulingBatchMapper batchMapper;
    private final PlnSchedulingBatchAllocationMapper batchAllocationMapper;
    private final RemoteMdmRouteEngineeringService remoteMdmRouteEngineeringService;
    private final RemoteMdmProcedureService remoteMdmProcedureService;
    private final RemoteClassesService remoteClassesService;
    private final RemoteCalendarService remoteCalendarService;
    private final RemoteTpmDeviceAvailabilityService remoteTpmDeviceAvailabilityService;
    private final RemoteMdmWorkforceService remoteMdmWorkforceService;
    private final RemoteMdmToolingService remoteMdmToolingService;
    private final RemoteMdmChangeoverService remoteMdmChangeoverService;
    private final RemoteMdmDurationCalibrationService remoteMdmDurationCalibrationService;

    private static String toolingOperationKey(String routeCode, String routeNodeCode) {
        return routeCode + "|" + routeNodeCode;
    }

    private static String workforceCandidateKey(String employeeCode, String processCode) {
        return employeeCode + "|" + processCode;
    }

    private void requireActiveTask(Long taskId) {
        if (lifecycleTaskMapper.acquireTransactionLock(taskId) == null) throw new CheckedException("排产任务不存在");
        var task = lifecycleTaskMapper.selectById(taskId);
        if (task == null || "CANCELLED".equals(task.getTaskStatus()))
            throw new CheckedException("已取消任务不得重新写入求解结果");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<Long, PlnOperationScheduleEvaluationDTO> scheduleTask(Long taskId, List<PlnIssueEntity> issues) {
        if (taskId == null || CollUtil.isEmpty(issues)) {
            return Map.of();
        }
        requireActiveTask(taskId);
        operationMapper.delete(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getTaskId, taskId));
        LocalDate rangeStart = issues.stream().map(PlnIssueEntity::getScheduledDate).filter(Objects::nonNull)
                .min(LocalDate::compareTo).orElse(LocalDate.now());
        SchedulingInputs inputs = loadInputs(issues, rangeStart);
        Map<String, List<BookedInterval>> bookings = loadBookings(taskId, null,
                rangeStart.minusDays(CHANGEOVER_LOOKBACK_DAYS).atStartOfDay(),
                rangeStart.plusDays(MAX_CALENDAR_DAYS).atTime(LocalTime.MAX));
        addDeviceUnavailableBookings(bookings, inputs.devices);
        addToolingUnavailableBookings(bookings, inputs.tooling);
        Map<String, BigDecimal> toolingLifeReservations = loadToolingLifeReservations(taskId, null);
        Map<Long, PlnOperationScheduleEvaluationDTO> result = new LinkedHashMap<>();
        for (PlnIssueEntity issue : issues) {
            com.kitex.mom.plan.service.SchedulingExecutionContext.check();
            PlanOutcome outcome = planIssue(taskId, issue, issue.getScheduledDate(), inputs, bookings,
                    toolingLifeReservations);
            outcome.operations.forEach(operationMapper::insert);
            applyEvaluation(issue, outcome.evaluation);
            if (issue.getId() != null) {
                result.put(issue.getId(), outcome.evaluation);
            }
        }
        return result;
    }

    @Override
    public List<PlnIssueEntity> optimizeGlobalSequence(List<PlnIssueEntity> issues, String strategy, Integer window, LocalDate start) {
        return optimizeGlobalSequence(issues, strategy, window, start, "GREEDY_WINDOW");
    }

    @Override
    public List<PlnIssueEntity> optimizeGlobalSequence(List<PlnIssueEntity> issues, String sequenceStrategy,
                                                       Integer crossPriorityWindowDays, LocalDate rangeStart, String solverCode) {
        if (CollUtil.isEmpty(issues)) return List.of();
        String strategy = StrUtil.blankToDefault(sequenceStrategy, "BALANCED_GLOBAL").trim().toUpperCase(Locale.ROOT);
        if (!Set.of("DELIVERY_FIRST", "BALANCED_GLOBAL", "CHANGEOVER_FIRST").contains(strategy))
            throw new CheckedException("不支持的全局序列策略");
        int window = Objects.requireNonNullElse(crossPriorityWindowDays, "DELIVERY_FIRST".equals(strategy) ? 0 : "CHANGEOVER_FIRST".equals(strategy) ? 7 : 2);
        if (window < 0 || window > 30) throw new CheckedException("交期窗口须为0至30天，不能静默截断配置");
        LocalDate start = Objects.requireNonNullElse(rangeStart, LocalDate.now());
        ChangeoverCatalog catalog = loadChangeoverCatalog(issues, start.minusDays(1).atStartOfDay(), start.plusDays(MAX_CALENDAR_DAYS).atTime(LocalTime.MAX));
        boolean preserve = "DELIVERY_FIRST".equals(strategy) || issues.size() < 2 || !catalog.verified || !catalog.governanceRequired;
        String observation = !catalog.verified ? "换型目录不可核验，后续有限产能须故障关闭" : preserve ? "保持规则评分交期顺序" : "按冻结交期窗口与换型估计求解，实际资源仍须独立校验";
        var jobs = issues.stream().map(i -> new com.kitex.mom.plan.service.solver.SequenceProblem.Job(
                i.getId() == null ? null : String.valueOf(i.getId()), i.getProductCode(), catalog.family(i.getProductCode()), i.getDemandTime())).toList();
        Set<String> families = jobs.stream().map(com.kitex.mom.plan.service.solver.SequenceProblem.Job::family).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, Map<String, Long>> costs = new TreeMap<>();
        Set<List<String>> forbidden = new HashSet<>();
        for (var rule : catalog.rules) {
            if (!families.contains(rule.getFromFamilyCode()) || !families.contains(rule.getToFamilyCode())) continue;
            if (Boolean.TRUE.equals(rule.getForbidden()))
                forbidden.add(List.of(rule.getFromFamilyCode(), rule.getToFamilyCode()));
            if (rule.getTotalMinutes() != null)
                costs.computeIfAbsent(rule.getFromFamilyCode(), k -> new TreeMap<>()).merge(rule.getToFamilyCode(), rule.getTotalMinutes(), Math::max);
        }
        for (var arc : forbidden) costs.computeIfAbsent(arc.get(0), k -> new TreeMap<>()).put(arc.get(1), 1_000_000L);
        var problem = new com.kitex.mom.plan.service.solver.SequenceProblem("APS_SEQUENCE_V1", strategy, window, preserve, observation,
                com.kitex.mom.plan.service.solver.SequenceSolverRegistry.json(catalog), jobs, costs);
        var solved = sequenceSolvers.solve(solverCode, problem);
        Map<String, PlnIssueEntity> byId = issues.stream().collect(Collectors.toMap(i -> String.valueOf(i.getId()), Function.identity()));
        List<PlnIssueEntity> ordered = new ArrayList<>();
        String previous = null;
        for (String id : solved.solution().orderedIds()) {
            var issue = byId.get(id);
            String family = catalog.family(issue.getProductCode());
            appendGlobalSequenceEvidence(issue, strategy, window, ordered.size() + 1, previous, family,
                    preserve ? 0 : estimatedTransitionMinutes(previous, family, catalog),
                    observation + "；求解器=" + sequenceSolvers.normalize(solverCode) + "；" + solved.solution().termination() + "；采用=" + solved.solution().chosenMethod());
            issue.setSequenceEvidenceJson(solved.evidenceJson());
            ordered.add(issue);
            previous = family;
        }
        return ordered;
    }

    private long estimatedTransitionMinutes(String fromFamily, String toFamily, ChangeoverCatalog catalog) {
        if (StrUtil.isBlank(fromFamily) || StrUtil.isBlank(toFamily) || Objects.equals(fromFamily, toFamily)) return 0L;
        List<MdmChangeoverRuleEntity> matched = catalog.rules.stream()
                .filter(rule -> Objects.equals(fromFamily, rule.getFromFamilyCode()))
                .filter(rule -> Objects.equals(toFamily, rule.getToFamilyCode())).toList();
        if (matched.stream().anyMatch(rule -> Boolean.TRUE.equals(rule.getForbidden()))) return 1_000_000L;
        return matched.stream().map(MdmChangeoverRuleEntity::getTotalMinutes).filter(Objects::nonNull)
                .max(Long::compareTo).orElse(240L);
    }

    @SuppressWarnings("unchecked")
    private void appendGlobalSequenceEvidence(PlnIssueEntity issue, String strategy, int windowDays, int rank,
                                              String precedingFamily, String currentFamily,
                                              long estimatedChangeoverMinutes, String explanation) {
        try {
            Map<String, Object> breakdown = StrUtil.isBlank(issue.getScoreBreakdown()) ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(OBJECT_MAPPER.readValue(issue.getScoreBreakdown(), Map.class));
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("strategy", strategy);
            evidence.put("crossPriorityWindowDays", windowDays);
            evidence.put("globalRank", rank);
            evidence.put("precedingFamily", precedingFamily);
            evidence.put("currentFamily", currentFamily);
            evidence.put("estimatedChangeoverMinutes", estimatedChangeoverMinutes);
            evidence.put("explanation", explanation);
            breakdown.put("_globalSequence", evidence);
            issue.setScoreBreakdown(toJson(breakdown));
        } catch (Exception exception) {
            throw new CheckedException("全局序列证据生成失败：" + issue.getIssueNo());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<Long, PlnOperationScheduleEvaluationDTO> scheduleTaskBatches(
            Long taskId, PlnSchedulingBatchLedgerDTO ledger) {
        if (taskId == null || ledger == null || !taskId.equals(ledger.getTaskId())
                || !Boolean.TRUE.equals(ledger.getQuantityBalanced()) || CollUtil.isEmpty(ledger.getBatches())) {
            throw new CheckedException("拆合批重排缺少有效且数量守恒的批次账");
        }
        requireActiveTask(taskId);
        List<PlnSchedulingBatchAllocationEntity> allAllocations = ledger.getBatches().stream()
                .flatMap(item -> item.getAllocations().stream()).toList();
        List<Long> issueIds = allAllocations.stream().map(PlnSchedulingBatchAllocationEntity::getIssueId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, PlnIssueEntity> issues = issueMapper.selectBatchIds(issueIds).stream()
                .collect(Collectors.toMap(PlnIssueEntity::getId, item -> item));
        if (issues.size() != issueIds.size()) {
            throw new CheckedException("拆合批重排引用的计划已变化");
        }
        Map<Long, PlnSchedulingTaskDetailEntity> details = taskDetailMapper.selectList(
                        new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                                .eq(PlnSchedulingTaskDetailEntity::getTaskId, taskId))
                .stream().collect(Collectors.toMap(PlnSchedulingTaskDetailEntity::getIssueId, item -> item));
        if (!details.keySet().containsAll(issueIds)) {
            throw new CheckedException("拆合批重排引用了非本任务计划");
        }

        operationMapper.delete(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getTaskId, taskId));
        LocalDate rangeStart = ledger.getBatches().stream()
                .map(item -> item.getHeader().getRequestedStartDate()).filter(Objects::nonNull)
                .min(LocalDate::compareTo).orElseGet(() -> details.values().stream()
                        .map(PlnSchedulingTaskDetailEntity::getScheduledDate).filter(Objects::nonNull)
                        .min(LocalDate::compareTo).orElse(LocalDate.now()));
        List<PlnIssueEntity> issueRefs = new ArrayList<>(issues.values());
        SchedulingInputs inputs = loadInputs(issueRefs, rangeStart);
        Map<String, List<BookedInterval>> bookings = loadBookings(taskId, null,
                rangeStart.minusDays(CHANGEOVER_LOOKBACK_DAYS).atStartOfDay(),
                rangeStart.plusDays(MAX_CALENDAR_DAYS).atTime(LocalTime.MAX));
        addDeviceUnavailableBookings(bookings, inputs.devices);
        addToolingUnavailableBookings(bookings, inputs.tooling);
        Map<String, BigDecimal> toolingLifeReservations = loadToolingLifeReservations(taskId, null);
        Map<Long, List<PlnOperationScheduleEvaluationDTO>> evaluationsByIssue = new LinkedHashMap<>();
        Map<Long, List<PlnSchedulingOperationEntity>> operationsByIssue = new LinkedHashMap<>();

        for (PlnSchedulingBatchLedgerDTO.Batch item : ledger.getBatches()) {
            PlnSchedulingBatchEntity batch = item.getHeader();
            if (batch == null || batch.getId() == null || CollUtil.isEmpty(item.getAllocations())) {
                throw new CheckedException("批次账存在无效批次头或来源分配");
            }
            PlnIssueEntity source = issues.get(batch.getPrimaryIssueId());
            if (source == null) {
                throw new CheckedException("批次[" + batch.getBatchNo() + "]主计划不存在");
            }
            PlnIssueEntity schedulingIssue = new PlnIssueEntity();
            BeanUtils.copyProperties(source, schedulingIssue);
            schedulingIssue.setIssueNo(batch.getBatchNo());
            schedulingIssue.setNum(batch.getPlannedQuantity());
            LocalDate requestedStart = batch.getRequestedStartDate();
            if (requestedStart == null) {
                requestedStart = item.getAllocations().stream().map(PlnSchedulingBatchAllocationEntity::getIssueId)
                        .map(details::get).filter(Objects::nonNull)
                        .map(PlnSchedulingTaskDetailEntity::getScheduledDate).filter(Objects::nonNull)
                        .min(LocalDate::compareTo).orElse(rangeStart);
            }
            schedulingIssue.setScheduledDate(requestedStart);
            LocalDate materialReady = item.getAllocations().stream()
                    .map(PlnSchedulingBatchAllocationEntity::getIssueId).map(details::get).filter(Objects::nonNull)
                    .map(PlnSchedulingTaskDetailEntity::getMaterialAvailableDate).filter(Objects::nonNull)
                    .max(LocalDate::compareTo).orElse(null);
            schedulingIssue.setMaterialAvailableDate(materialReady);
            schedulingIssue.setMaterialAvailableTime(item.getAllocations().stream()
                    .map(PlnSchedulingBatchAllocationEntity::getIssueId).map(details::get)
                    .filter(Objects::nonNull).map(PlnSchedulingTaskDetailEntity::getMaterialAvailableTime)
                    .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null));
            List<PlnSchedulingTaskDetailEntity> sourceMaterialDetails = item.getAllocations().stream()
                    .map(PlnSchedulingBatchAllocationEntity::getIssueId).map(details::get)
                    .filter(Objects::nonNull).toList();
            applyBatchMaterialContext(schedulingIssue, sourceMaterialDetails);

            PlanOutcome outcome = planIssue(taskId, schedulingIssue, requestedStart, inputs, bookings,
                    toolingLifeReservations);
            for (PlnSchedulingOperationEntity operation : outcome.operations) {
                operation.setIssueId(batch.getPrimaryIssueId());
                operation.setIssueNo(batch.getPrimaryIssueNo());
                operation.setSchedulingBatchId(batch.getId());
                operation.setSchedulingBatchNo(batch.getBatchNo());
                operation.setSchedulingBatchType(batch.getBatchType());
                operation.setBatchAllocationSnapshot(batch.getAllocationSnapshot());
                operationMapper.insert(operation);
            }
            batch.setScheduledStartTime(firstStart(outcome.operations));
            batch.setScheduledEndTime(lastEnd(outcome.operations));
            batch.setBatchStatus(Boolean.TRUE.equals(outcome.evaluation.getHardBlocked())
                    || outcome.operations.isEmpty() ? "BLOCKED" : "PLANNED");
            batchMapper.updateById(batch);
            for (PlnSchedulingBatchAllocationEntity allocation : item.getAllocations()) {
                evaluationsByIssue.computeIfAbsent(allocation.getIssueId(), key -> new ArrayList<>())
                        .add(outcome.evaluation);
                operationsByIssue.computeIfAbsent(allocation.getIssueId(), key -> new ArrayList<>())
                        .addAll(outcome.operations);
            }
        }

        Map<Long, PlnOperationScheduleEvaluationDTO> result = new LinkedHashMap<>();
        for (Long issueId : issueIds) {
            List<PlnOperationScheduleEvaluationDTO> evaluations = evaluationsByIssue.getOrDefault(issueId, List.of());
            List<PlnSchedulingOperationEntity> operations = operationsByIssue.getOrDefault(issueId, List.of());
            boolean blocked = evaluations.stream().anyMatch(item -> Boolean.TRUE.equals(item.getHardBlocked()));
            String status = evaluations.stream().filter(item -> Boolean.TRUE.equals(item.getHardBlocked()))
                    .map(PlnOperationScheduleEvaluationDTO::getStatus).filter(StrUtil::isNotBlank).findFirst()
                    .orElseGet(() -> evaluations.stream().map(PlnOperationScheduleEvaluationDTO::getStatus)
                            .filter(StrUtil::isNotBlank).findFirst()
                            .orElse(SchedulingOperationStatusEnum.OPERATION_MISSING.getCode()));
            String summary = "涉及" + evaluations.size() + "个排产批次；" + evaluations.stream()
                    .map(PlnOperationScheduleEvaluationDTO::getSummary).filter(StrUtil::isNotBlank)
                    .distinct().collect(Collectors.joining("；"));
            PlnOperationScheduleEvaluationDTO evaluation = new PlnOperationScheduleEvaluationDTO()
                    .setIssueId(issueId).setStatus(status).setHardBlocked(blocked).setSummary(summary)
                    .setOperationCount(operations.size()).setFirstStartTime(firstStart(operations))
                    .setLastEndTime(lastEnd(operations));
            PlnSchedulingTaskDetailEntity detail = details.get(issueId);
            detail.setOperationScheduleStatus(status);
            detail.setOperationHardBlocked(blocked);
            detail.setOperationScheduleSummary(summary);
            detail.setOperationCount(operations.size());
            detail.setOperationStartTime(firstStart(operations));
            detail.setOperationEndTime(lastEnd(operations));
            if (firstStart(operations) != null) detail.setScheduledDate(firstStart(operations).toLocalDate());
            if (lastEnd(operations) != null) detail.setScheduledEndDate(lastEnd(operations).toLocalDate());
            taskDetailMapper.updateById(detail);
            result.put(issueId, evaluation);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnOperationScheduleEvaluationDTO rescheduleIssue(Long taskId, PlnIssueEntity issue, LocalDate startDate) {
        if (taskId == null || issue == null || issue.getId() == null || startDate == null) {
            throw new CheckedException("工序重排参数不完整");
        }
        requireActiveTask(taskId);
        SchedulingInputs inputs = loadInputs(List.of(issue), startDate);
        Map<String, List<BookedInterval>> bookings = loadBookings(taskId, issue.getId(),
                startDate.minusDays(CHANGEOVER_LOOKBACK_DAYS).atStartOfDay(),
                startDate.plusDays(MAX_CALENDAR_DAYS).atTime(LocalTime.MAX));
        addDeviceUnavailableBookings(bookings, inputs.devices);
        addToolingUnavailableBookings(bookings, inputs.tooling);
        Map<String, BigDecimal> toolingLifeReservations = loadToolingLifeReservations(taskId, issue.getId());
        PlanOutcome outcome = planIssue(taskId, issue, startDate, inputs, bookings, toolingLifeReservations);
        if (Boolean.TRUE.equals(outcome.evaluation.getHardBlocked())) {
            throw new CheckedException("工序级排程失败：" + outcome.evaluation.getSummary());
        }
        operationMapper.delete(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getTaskId, taskId)
                .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                .eq(PlnSchedulingOperationEntity::getLockStatus, false));
        outcome.operations.forEach(operationMapper::insert);
        applyEvaluation(issue, outcome.evaluation);
        return outcome.evaluation;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnOperationScheduleEvaluationDTO previewLocalReplan(Long proposalTaskId, Long originalTaskId,
                                                                PlnIssueEntity issue, LocalDateTime startTime) {
        return previewLocalReplan(proposalTaskId, originalTaskId, issue, startTime, "RECOVERY_FIRST");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnOperationScheduleEvaluationDTO previewLocalReplan(Long proposalTaskId, Long originalTaskId,
                                                                PlnIssueEntity issue, LocalDateTime startTime,
                                                                String candidateStrategy) {
        if (proposalTaskId == null || originalTaskId == null || issue == null || issue.getId() == null || startTime == null) {
            throw new CheckedException("局部重排预演参数不完整");
        }
        String strategy = StrUtil.blankToDefault(candidateStrategy, "RECOVERY_FIRST").trim().toUpperCase();
        if (!Set.of("RECOVERY_FIRST", "MINIMUM_DISRUPTION", "CHANGEOVER_STABLE").contains(strategy)) {
            throw new CheckedException("不支持的局部重排候选策略：" + candidateStrategy);
        }
        LocalDate startDate = startTime.toLocalDate();
        SchedulingInputs inputs = loadInputs(List.of(issue), startDate);
        Map<String, List<BookedInterval>> bookings = loadBookings(proposalTaskId, null,
                startDate.minusDays(CHANGEOVER_LOOKBACK_DAYS).atStartOfDay(),
                startDate.plusDays(MAX_CALENDAR_DAYS).atTime(LocalTime.MAX),
                originalTaskId, issue.getId());
        addDeviceUnavailableBookings(bookings, inputs.devices);
        addToolingUnavailableBookings(bookings, inputs.tooling);
        Map<String, BigDecimal> toolingLifeReservations = loadToolingLifeReservations(
                proposalTaskId, null, originalTaskId, issue.getId());
        List<PlnSchedulingOperationEntity> preferred = operationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getTaskId, originalTaskId)
                        .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq));
        if (preferred.isEmpty()) throw new CheckedException("局部重排缺少原正式工序快照");
        PlanOutcome outcome = planIssue(proposalTaskId, issue, startTime, inputs, bookings,
                toolingLifeReservations, new LocalReplanChoicePolicy(strategy, preferred));
        if (Boolean.TRUE.equals(outcome.evaluation.getHardBlocked())
                && !SchedulingOperationStatusEnum.COMPLETION_AFTER_DEMAND.getCode().equals(outcome.evaluation.getStatus())) {
            throw new CheckedException("局部重排预演失败：" + outcome.evaluation.getSummary());
        }
        outcome.operations.forEach(operationMapper::insert);
        applyEvaluation(issue, outcome.evaluation);
        return outcome.evaluation;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<Long, PlnOperationScheduleEvaluationDTO> previewInsertionReplan(
            Long proposalTaskId, List<PlnIssueEntity> orderedIssues,
            Map<Long, Long> excludedFormalTasks, Map<Long, LocalDateTime> requestedStarts) {
        return previewInsertionReplan(proposalTaskId, orderedIssues, excludedFormalTasks, requestedStarts,
                "RECOVERY_FIRST");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<Long, PlnOperationScheduleEvaluationDTO> previewInsertionReplan(
            Long proposalTaskId, List<PlnIssueEntity> orderedIssues,
            Map<Long, Long> excludedFormalTasks, Map<Long, LocalDateTime> requestedStarts,
            String candidateStrategy) {
        if (proposalTaskId == null || CollUtil.isEmpty(orderedIssues) || requestedStarts == null) {
            throw new CheckedException("插单联合预演参数不完整");
        }
        String strategy = StrUtil.blankToDefault(candidateStrategy, "RECOVERY_FIRST").trim().toUpperCase();
        if (!Set.of("RECOVERY_FIRST", "MINIMUM_DISRUPTION", "CHANGEOVER_STABLE").contains(strategy)) {
            throw new CheckedException("不支持的插单候选策略：" + candidateStrategy);
        }
        Set<Long> issueIds = orderedIssues.stream().map(PlnIssueEntity::getId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (issueIds.size() != orderedIssues.size() || !requestedStarts.keySet().containsAll(issueIds)) {
            throw new CheckedException("插单联合预演计划为空、重复或缺少期望开始时刻");
        }
        Map<Long, Long> excluded = excludedFormalTasks == null ? Map.of() : excludedFormalTasks;
        if (!issueIds.containsAll(excluded.keySet())) {
            throw new CheckedException("插单联合预演释放了范围外正式计划");
        }
        operationMapper.delete(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getTaskId, proposalTaskId));
        LocalDateTime rangeStart = requestedStarts.values().stream().filter(Objects::nonNull)
                .min(LocalDateTime::compareTo).orElseThrow(() -> new CheckedException("插单预演开始时刻不能为空"));
        SchedulingInputs inputs = loadInputs(orderedIssues, rangeStart.toLocalDate());
        Map<String, List<BookedInterval>> bookings = loadBookingsExcludingScope(proposalTaskId, excluded,
                rangeStart.minusDays(CHANGEOVER_LOOKBACK_DAYS),
                rangeStart.plusDays(MAX_CALENDAR_DAYS).with(LocalTime.MAX));
        addDeviceUnavailableBookings(bookings, inputs.devices);
        addToolingUnavailableBookings(bookings, inputs.tooling);
        Map<String, BigDecimal> toolingLifeReservations = loadToolingLifeReservationsExcludingScope(
                proposalTaskId, excluded);
        Map<Long, PlnOperationScheduleEvaluationDTO> result = new LinkedHashMap<>();
        for (PlnIssueEntity issue : orderedIssues) {
            List<PlnSchedulingOperationEntity> preferred = Optional.ofNullable(excluded.get(issue.getId()))
                    .map(taskId -> operationMapper.selectList(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                            .eq(PlnSchedulingOperationEntity::getTaskId, taskId)
                            .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                            .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq)))
                    .orElse(List.of());
            if (excluded.containsKey(issue.getId()) && preferred.isEmpty())
                throw new CheckedException("插单重排缺少原正式工序快照");
            PlanOutcome outcome = planIssue(proposalTaskId, issue, requestedStarts.get(issue.getId()), inputs,
                    bookings, toolingLifeReservations, new LocalReplanChoicePolicy(strategy, preferred));
            if (Boolean.TRUE.equals(outcome.evaluation.getHardBlocked())
                    && !SchedulingOperationStatusEnum.COMPLETION_AFTER_DEMAND.getCode()
                    .equals(outcome.evaluation.getStatus())) {
                throw new CheckedException("插单联合预演失败，计划[" + issue.getIssueNo() + "]："
                        + outcome.evaluation.getSummary());
            }
            outcome.operations.forEach(operationMapper::insert);
            applyEvaluation(issue, outcome.evaluation);
            result.put(issue.getId(), outcome.evaluation);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void validateLocalReplan(PlnIssueEntity issue, List<PlnSchedulingOperationEntity> operations,
                                    Long originalTaskId, Long issueId) {
        if (issue == null || CollUtil.isEmpty(operations) || originalTaskId == null || issueId == null) {
            throw new CheckedException("局部重排发布校验参数不完整");
        }
        operations.stream().flatMap(item -> operationKeys(item).stream()).distinct().sorted()
                .forEach(operationMapper::acquireResourceTransactionLock);
        LocalDateTime minStart = operations.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                .min(LocalDateTime::compareTo).orElseThrow();
        LocalDateTime maxEnd = operations.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                .max(LocalDateTime::compareTo).orElseThrow();
        List<PlnSchedulingOperationEntity> others = operationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getLockStatus, true)
                        .and(w -> w.ne(PlnSchedulingOperationEntity::getTaskId, originalTaskId)
                                .or().ne(PlnSchedulingOperationEntity::getIssueId, issueId))
                        .lt(PlnSchedulingOperationEntity::getScheduledStartTime, maxEnd)
                        .gt(PlnSchedulingOperationEntity::getScheduledEndTime,
                                minStart.minusDays(CHANGEOVER_LOOKBACK_DAYS)));
        List<PlnSchedulingOperationEntity> copies = new ArrayList<>();
        long syntheticId = -1;
        for (PlnSchedulingOperationEntity source : operations) {
            PlnSchedulingOperationEntity copy = new PlnSchedulingOperationEntity();
            BeanUtils.copyProperties(source, copy);
            copy.setId(syntheticId--);
            copies.add(copy);
        }
        RouteCatalog routes = loadRoutes(List.of(issue));
        ProcedureCatalog procedures = loadProcedures();
        DeviceAvailabilityCatalog devices = loadDeviceAvailability(copies.stream()
                        .map(PlnSchedulingOperationEntity::getDeviceCode).filter(StrUtil::isNotBlank).distinct().toList(),
                minStart, maxEnd);
        WorkforceAvailabilityCatalog workforce = loadWorkforceAvailability(copies.stream()
                        .map(PlnSchedulingOperationEntity::getEmployeeCode).filter(StrUtil::isNotBlank).distinct().toList(),
                copies.stream().map(PlnSchedulingOperationEntity::getProcessCode)
                        .filter(StrUtil::isNotBlank).distinct().toList(), minStart, maxEnd);
        ToolingAvailabilityCatalog tooling = loadToolingAvailability(routes, minStart, maxEnd);
        ChangeoverCatalog changeover = loadChangeoverCatalog(List.of(issue), minStart, maxEnd);
        DurationCalibrationCatalog durationCalibration = loadDurationCalibrationCatalog(List.of(issue), routes);
        Map<String, BigDecimal> toolingLifeReservations = loadToolingLifeReservations(
                null, null, originalTaskId, issueId);
        PlnSchedulingTaskDetailEntity detail = new PlnSchedulingTaskDetailEntity();
        detail.setIssueId(issueId);
        // 临时复核明细沿用已核定计划的真实组织，不能因未落库而缺失工厂。
        detail.setFactoryCode(issue.getFactoryCode());
        detail.setTenantId(issue.getTenantId());
        detail.setProductCode(issue.getProductCode());
        copyEngineeringBinding(issue, detail);
        detail.setScheduledDate(minStart.toLocalDate());
        // 局部重排允许在明确延期上限和人审下产出LATE方案，交期阈值由上层方案政策校验。
        detail.setDemandTime(null);
        ValidationResult validation = validateOperations(detail, copies, copies, others, routes, procedures,
                devices, workforce, tooling, changeover, durationCalibration, toolingLifeReservations);
        if (validation.hardBlocked) {
            throw new CheckedException("局部重排实时复核失败：" + validation.summary);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void validateInsertionReplan(Map<Long, PlnIssueEntity> issues,
                                        Map<Long, List<PlnSchedulingOperationEntity>> operations,
                                        Map<Long, Long> excludedFormalTasks) {
        if (issues == null || issues.isEmpty() || operations == null
                || !operations.keySet().equals(issues.keySet())) {
            throw new CheckedException("插单联合发布校验范围不完整");
        }
        Map<Long, Long> excluded = excludedFormalTasks == null ? Map.of() : excludedFormalTasks;
        // 回退会移除插单本身的正式排程，因此释放范围可以比候选计划集合多一张；
        // 但每个额外范围都必须精确对应当前存在的正式锁定工序，禁止伪造(issueId,taskId)绕过占用。
        for (Map.Entry<Long, Long> scope : excluded.entrySet()) {
            Long count = operationMapper.selectCount(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                    .eq(PlnSchedulingOperationEntity::getIssueId, scope.getKey())
                    .eq(PlnSchedulingOperationEntity::getTaskId, scope.getValue())
                    .eq(PlnSchedulingOperationEntity::getLockStatus, true));
            if (count == null || count == 0) {
                throw new CheckedException("插单联合发布释放范围与当前正式排程不一致");
            }
        }
        List<PlnSchedulingOperationEntity> copies = new ArrayList<>();
        Map<Long, List<PlnSchedulingOperationEntity>> copiesByIssue = new LinkedHashMap<>();
        long syntheticId = -1L;
        for (Map.Entry<Long, List<PlnSchedulingOperationEntity>> entry : operations.entrySet()) {
            if (CollUtil.isEmpty(entry.getValue())) {
                throw new CheckedException("计划[" + entry.getKey() + "]缺少插单候选工序");
            }
            List<PlnSchedulingOperationEntity> rows = new ArrayList<>();
            for (PlnSchedulingOperationEntity source : entry.getValue()) {
                PlnSchedulingOperationEntity copy = new PlnSchedulingOperationEntity();
                BeanUtils.copyProperties(source, copy);
                copy.setId(syntheticId--);
                rows.add(copy);
                copies.add(copy);
            }
            copiesByIssue.put(entry.getKey(), rows);
        }
        copies.stream().flatMap(item -> operationKeys(item).stream()).distinct().sorted()
                .forEach(operationMapper::acquireResourceTransactionLock);
        LocalDateTime minStart = copies.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElseThrow();
        LocalDateTime maxEnd = copies.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElseThrow();
        List<PlnSchedulingOperationEntity> lockedOthers = operationMapper.selectList(
                        new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                                .eq(PlnSchedulingOperationEntity::getLockStatus, true)
                                .lt(PlnSchedulingOperationEntity::getScheduledStartTime, maxEnd)
                                .gt(PlnSchedulingOperationEntity::getScheduledEndTime,
                                        minStart.minusDays(CHANGEOVER_LOOKBACK_DAYS)))
                .stream().filter(item -> !scopeContains(excluded, item)).toList();
        List<PlnIssueEntity> issueRefs = new ArrayList<>(issues.values());
        RouteCatalog routes = loadRoutes(issueRefs);
        ProcedureCatalog procedures = loadProcedures();
        DeviceAvailabilityCatalog devices = loadDeviceAvailability(copies.stream()
                .map(PlnSchedulingOperationEntity::getDeviceCode).filter(StrUtil::isNotBlank)
                .distinct().toList(), minStart, maxEnd);
        WorkforceAvailabilityCatalog workforce = loadWorkforceAvailability(copies.stream()
                        .flatMap(item -> csvList(item.getEmployeeCode()).stream()).distinct().toList(),
                copies.stream().map(PlnSchedulingOperationEntity::getProcessCode)
                        .filter(StrUtil::isNotBlank).distinct().toList(), minStart, maxEnd);
        ToolingAvailabilityCatalog tooling = loadToolingAvailability(routes, minStart, maxEnd);
        ChangeoverCatalog changeover = loadChangeoverCatalog(issueRefs, minStart, maxEnd);
        DurationCalibrationCatalog durationCalibration = loadDurationCalibrationCatalog(issueRefs, routes);
        Map<String, BigDecimal> lifeReservations = loadToolingLifeReservationsExcludingScope(null, excluded);
        for (Map.Entry<Long, PlnIssueEntity> entry : issues.entrySet()) {
            List<PlnSchedulingOperationEntity> rows = copiesByIssue.get(entry.getKey());
            PlnSchedulingTaskDetailEntity detail = new PlnSchedulingTaskDetailEntity();
            detail.setIssueId(entry.getKey());
            detail.setFactoryCode(entry.getValue().getFactoryCode());
            detail.setTenantId(entry.getValue().getTenantId());
            detail.setProductCode(entry.getValue().getProductCode());
            copyEngineeringBinding(entry.getValue(), detail);
            detail.setScheduledDate(firstStart(rows).toLocalDate());
            // 插单的延期上限由上层策略逐订单校验，这里只校验资源和主数据硬约束。
            detail.setDemandTime(null);
            ValidationResult validation = validateOperations(detail, rows, copies, lockedOthers,
                    routes, procedures, devices, workforce, tooling, changeover, durationCalibration, lifeReservations);
            if (validation.hardBlocked) {
                throw new CheckedException("插单联合实时复核失败，计划[" + entry.getValue().getIssueNo()
                        + "]：" + validation.summary);
            }
        }
    }

    @Override
    public void validateDerivedCalendar(Long taskId) {
        if (taskId == null) throw new CheckedException("专业调整候选任务不能为空");
        List<PlnSchedulingOperationEntity> operations = operationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getTaskId, taskId));
        if (operations.isEmpty() || operations.stream().anyMatch(item -> item.getScheduledStartTime() == null
                || item.getScheduledEndTime() == null || item.getActiveMinutes() == null
                || item.getActiveMinutes() <= 0))
            throw new CheckedException("专业调整候选缺少完整的工序时间与有效工时");
        LocalDate first = operations.stream().map(item -> item.getScheduledStartTime().toLocalDate())
                .min(LocalDate::compareTo).orElseThrow();
        LocalDate last = operations.stream().map(item -> item.getScheduledEndTime().toLocalDate())
                .max(LocalDate::compareTo).orElseThrow();
        WorkingCalendar calendar = new WorkingCalendar(first.minusDays(1), last.plusDays(1),
                loadShifts(), loadWorkDays(first.minusDays(1), last.plusDays(1)));
        for (PlnSchedulingOperationEntity operation : operations) {
            LocalDateTime verifiedEnd = calendar.addWorkingMinutes(
                    operation.getScheduledStartTime(), operation.getActiveMinutes());
            // 人员联合日历可能比工厂班次更窄；其精确结束时刻由 validateBeforeLock 再核验。
            boolean valid = StrUtil.isBlank(operation.getEmployeeCode())
                    ? Objects.equals(verifiedEnd, operation.getScheduledEndTime())
                    : verifiedEnd != null && !verifiedEnd.isAfter(operation.getScheduledEndTime());
            if (!valid)
                throw new CheckedException("工序[" + operation.getProcessCode()
                        + "]调整后未落在有效班次/工作日历，或完工时刻与有效工时不符");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void validateBeforeLock(Long taskId, List<PlnSchedulingTaskDetailEntity> details) {
        if (taskId == null || CollUtil.isEmpty(details)) {
            return;
        }
        List<PlnSchedulingOperationEntity> taskOperations = operationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getTaskId, taskId)
                        .orderByAsc(PlnSchedulingOperationEntity::getIssueId)
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq));
        Map<Long, List<PlnSchedulingOperationEntity>> grouped = taskOperations.stream()
                .filter(item -> item.getIssueId() != null)
                .collect(Collectors.groupingBy(PlnSchedulingOperationEntity::getIssueId,
                        LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<PlnSchedulingOperationEntity>> groupedByBatch = taskOperations.stream()
                .filter(item -> item.getSchedulingBatchId() != null)
                .collect(Collectors.groupingBy(PlnSchedulingOperationEntity::getSchedulingBatchId,
                        LinkedHashMap::new, Collectors.toList()));
        List<PlnSchedulingBatchEntity> taskBatches = batchMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingBatchEntity>()
                        .eq(PlnSchedulingBatchEntity::getTaskId, taskId));
        boolean batchMode = !taskBatches.isEmpty();
        List<PlnSchedulingBatchAllocationEntity> taskAllocations = batchMode
                ? batchAllocationMapper.selectList(new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                .eq(PlnSchedulingBatchAllocationEntity::getTaskId, taskId)) : List.of();
        Map<Long, List<Long>> batchIdsByIssue = taskAllocations.stream().collect(Collectors.groupingBy(
                PlnSchedulingBatchAllocationEntity::getIssueId, LinkedHashMap::new,
                Collectors.mapping(PlnSchedulingBatchAllocationEntity::getBatchId, Collectors.toList())));
        Map<Long, PlnSchedulingTaskDetailEntity> detailByIssue = details.stream().collect(Collectors.toMap(
                PlnSchedulingTaskDetailEntity::getIssueId, item -> item));
        // 所有任务按相同资源键排序加事务锁，既避免并发确认的幻读，也避免多资源加锁顺序不一致造成死锁。
        taskOperations.stream().flatMap(item -> operationKeys(item).stream()).distinct().sorted()
                .forEach(operationMapper::acquireResourceTransactionLock);
        LocalDateTime minStart = taskOperations.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(LocalDateTime.now());
        LocalDateTime maxEnd = taskOperations.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(minStart.plusDays(1));
        List<PlnSchedulingOperationEntity> lockedOthers = operationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getLockStatus, true)
                        .ne(PlnSchedulingOperationEntity::getTaskId, taskId)
                        .lt(PlnSchedulingOperationEntity::getScheduledStartTime, maxEnd)
                        .gt(PlnSchedulingOperationEntity::getScheduledEndTime,
                                minStart.minusDays(CHANGEOVER_LOOKBACK_DAYS)));

        List<PlnIssueEntity> issueRefs = details.stream().map(detail -> {
            PlnIssueEntity issue = new PlnIssueEntity();
            issue.setId(detail.getIssueId());
            issue.setProductCode(detail.getProductCode());
            issue.setScheduledDate(detail.getScheduledDate());
            issue.setEngineeringReleaseId(detail.getEngineeringReleaseId());
            issue.setEngineeringContentHash(detail.getEngineeringContentHash());
            issue.setEngineeringVersionNo(detail.getEngineeringVersionNo());
            issue.setEngineeringRouteId(detail.getEngineeringRouteId());
            issue.setEngineeringRouteCode(detail.getProcessRouteCode());
            issue.setEngineeringBomId(detail.getEngineeringBomId());
            issue.setEngineeringBomVersion(detail.getEngineeringBomVersion());
            issue.setFactoryCode(taskOperations.stream().map(PlnSchedulingOperationEntity::getFactoryCode)
                    .filter(StrUtil::isNotBlank).findFirst().orElse(null));
            return issue;
        }).toList();
        RouteCatalog routes = loadRoutes(issueRefs);
        ProcedureCatalog procedures = loadProcedures();
        DeviceAvailabilityCatalog devices = loadDeviceAvailability(
                taskOperations.stream().map(PlnSchedulingOperationEntity::getDeviceCode)
                        .filter(StrUtil::isNotBlank).distinct().toList(), minStart, maxEnd);
        WorkforceAvailabilityCatalog workforce = loadWorkforceAvailability(
                taskOperations.stream().map(PlnSchedulingOperationEntity::getEmployeeCode)
                        .filter(StrUtil::isNotBlank).distinct().toList(),
                taskOperations.stream().map(PlnSchedulingOperationEntity::getProcessCode)
                        .filter(StrUtil::isNotBlank).distinct().toList(), minStart, maxEnd);
        ToolingAvailabilityCatalog tooling = loadToolingAvailability(routes, minStart, maxEnd);
        ChangeoverCatalog changeover = loadChangeoverCatalog(issueRefs, minStart, maxEnd);
        DurationCalibrationCatalog durationCalibration = loadDurationCalibrationCatalog(issueRefs, routes);
        Map<String, BigDecimal> toolingLifeReservations = loadToolingLifeReservations(null, null);

        Map<Long, ValidationResult> validationByBatch = new LinkedHashMap<>();
        if (batchMode) {
            Map<Long, List<PlnSchedulingBatchAllocationEntity>> allocationsByBatch = taskAllocations.stream()
                    .collect(Collectors.groupingBy(PlnSchedulingBatchAllocationEntity::getBatchId));
            for (PlnSchedulingBatchEntity batch : taskBatches) {
                List<PlnSchedulingTaskDetailEntity> sourceDetails = allocationsByBatch
                        .getOrDefault(batch.getId(), List.of()).stream()
                        .map(PlnSchedulingBatchAllocationEntity::getIssueId).map(detailByIssue::get)
                        .filter(Objects::nonNull).toList();
                PlnSchedulingTaskDetailEntity primary = sourceDetails.stream().findFirst()
                        .orElseThrow(() -> new CheckedException("排产批次缺少有效来源计划"));
                validationByBatch.put(batch.getId(), validateOperations(
                        batchMaterialDetail(primary, sourceDetails),
                        groupedByBatch.getOrDefault(batch.getId(), List.of()), taskOperations,
                        lockedOthers, routes, procedures, devices, workforce, tooling, changeover,
                        durationCalibration, toolingLifeReservations));
            }
        }

        for (PlnSchedulingTaskDetailEntity detail : details) {
            List<PlnSchedulingOperationEntity> operations;
            ValidationResult validation;
            if (batchMode) {
                List<Long> batchIds = batchIdsByIssue.getOrDefault(detail.getIssueId(), List.of());
                operations = batchIds.stream().flatMap(batchId -> groupedByBatch
                        .getOrDefault(batchId, List.of()).stream()).toList();
                List<ValidationResult> validations = batchIds.stream().map(validationByBatch::get)
                        .filter(Objects::nonNull).toList();
                boolean blocked = validations.isEmpty()
                        || validations.stream().anyMatch(item -> item.hardBlocked);
                String status = validations.stream().filter(item -> item.hardBlocked)
                        .map(ValidationResult::status).findFirst()
                        .orElseGet(() -> validations.stream().map(ValidationResult::status).findFirst()
                                .orElse(SchedulingOperationStatusEnum.OPERATION_MISSING.getCode()));
                String summary = validations.isEmpty() ? "计划缺少排产批次工序"
                        : "涉及" + validations.size() + "个排产批次；" + validations.stream()
                        .map(ValidationResult::summary).distinct().collect(Collectors.joining("；"));
                validation = new ValidationResult(status, blocked, summary);
            } else {
                operations = grouped.getOrDefault(detail.getIssueId(), List.of());
                validation = validateOperations(detail, operations, taskOperations, lockedOthers,
                        routes, procedures, devices, workforce, tooling, changeover, durationCalibration,
                        toolingLifeReservations);
            }
            detail.setOperationScheduleStatus(validation.status);
            detail.setOperationHardBlocked(validation.hardBlocked);
            detail.setOperationScheduleSummary(validation.summary);
            detail.setOperationCount(operations.size());
            detail.setOperationStartTime(firstStart(operations));
            detail.setOperationEndTime(lastEnd(operations));
            taskDetailMapper.updateById(detail);
        }
    }

    @Override
    public void lockIssueOperations(Long taskId, Long issueId, LocalDateTime lockTime) {
        List<Long> batchIds = batchIdsForIssue(taskId, issueId);
        LambdaUpdateWrapper<PlnSchedulingOperationEntity> update = new LambdaUpdateWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getTaskId, taskId);
        if (batchIds.isEmpty()) {
            update.eq(PlnSchedulingOperationEntity::getIssueId, issueId);
        } else {
            update.in(PlnSchedulingOperationEntity::getSchedulingBatchId, batchIds);
        }
        operationMapper.update(null, update
                .set(PlnSchedulingOperationEntity::getLockStatus, true)
                .set(PlnSchedulingOperationEntity::getLockTime, lockTime));
    }

    @Override
    public List<PlnSchedulingOperationEntity> listTaskOperations(Long taskId, Long issueId, String resourceCode) {
        if (taskId == null) {
            throw new CheckedException("任务ID不能为空");
        }
        List<Long> batchIds = issueId == null ? List.of() : batchIdsForIssue(taskId, issueId);
        LambdaQueryWrapper<PlnSchedulingOperationEntity> query = new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getTaskId, taskId);
        if (issueId != null) {
            if (batchIds.isEmpty()) query.eq(PlnSchedulingOperationEntity::getIssueId, issueId);
            else query.in(PlnSchedulingOperationEntity::getSchedulingBatchId, batchIds);
        }
        return operationMapper.selectList(query
                .eq(StrUtil.isNotBlank(resourceCode), PlnSchedulingOperationEntity::getResourceCode, resourceCode)
                .orderByAsc(PlnSchedulingOperationEntity::getScheduledStartTime)
                .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq));
    }

    private List<Long> batchIdsForIssue(Long taskId, Long issueId) {
        if (taskId == null || issueId == null) return List.of();
        return batchAllocationMapper.selectList(new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                        .eq(PlnSchedulingBatchAllocationEntity::getTaskId, taskId)
                        .eq(PlnSchedulingBatchAllocationEntity::getIssueId, issueId))
                .stream().map(PlnSchedulingBatchAllocationEntity::getBatchId)
                .filter(Objects::nonNull).distinct().toList();
    }

    @Override
    public List<PlnSchedulingOperationEntity> listBatchOperations(Long taskId, Long batchId) {
        if (taskId == null || batchId == null) {
            throw new CheckedException("任务ID和排产批次ID不能为空");
        }
        return operationMapper.selectList(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                .eq(PlnSchedulingOperationEntity::getTaskId, taskId)
                .eq(PlnSchedulingOperationEntity::getSchedulingBatchId, batchId)
                .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq));
    }

    private PlanOutcome planIssue(Long taskId, PlnIssueEntity issue, LocalDate requestedStart,
                                  SchedulingInputs inputs, Map<String, List<BookedInterval>> bookings,
                                  Map<String, BigDecimal> toolingLifeReservations) {
        return planIssue(taskId, issue, requestedStart == null ? null : requestedStart.atStartOfDay(), inputs,
                bookings, toolingLifeReservations);
    }

    private PlanOutcome planIssue(Long taskId, PlnIssueEntity issue, LocalDateTime requestedStart,
                                  SchedulingInputs inputs, Map<String, List<BookedInterval>> bookings,
                                  Map<String, BigDecimal> toolingLifeReservations) {
        return planIssue(taskId, issue, requestedStart, inputs, bookings, toolingLifeReservations, null);
    }

    private PlanOutcome planIssue(Long taskId, PlnIssueEntity issue, LocalDateTime requestedStart,
                                  SchedulingInputs inputs, Map<String, List<BookedInterval>> bookings,
                                  Map<String, BigDecimal> toolingLifeReservations,
                                  LocalReplanChoicePolicy localPolicy) {
        if (!inputs.tooling.verified) {
            return PlanOutcome.blocked(issue.getId(), SchedulingOperationStatusEnum.TOOLING_UNVERIFIED.getCode(),
                    "MDM工装要求及节点归属不可核验，无法安全生成工序排程");
        }
        if (!inputs.changeover.verified) {
            return PlanOutcome.blocked(issue.getId(), SchedulingOperationStatusEnum.CHANGEOVER_UNVERIFIED.getCode(),
                    "MDM产品族与换型规则服务不可用，无法安全计算顺序相关准备时间");
        }
        if (!inputs.durationCalibration.verified) {
            return PlanOutcome.blocked(issue.getId(), SchedulingOperationStatusEnum.RESOURCE_UNVERIFIED.getCode(),
                    "MDM实际工时校准目录不可核验，无法确认当前生效的排程工时参数");
        }
        String schedulingFamily = inputs.changeover.family(issue.getProductCode());
        if (inputs.changeover.governanceRequired && StrUtil.isBlank(schedulingFamily)) {
            return PlanOutcome.blocked(issue.getId(), SchedulingOperationStatusEnum.CHANGEOVER_FAMILY_MISSING.getCode(),
                    "产品[" + issue.getProductCode() + "]未归属APS排产产品族，换型治理启用后禁止无族排产");
        }
        String routeProblem = inputs.routes.failures.get(routeKey(issue));
        MdmProcessRouteDTO route = inputs.routes.byIssue.get(routeKey(issue));
        if (route == null) {
            String summary = routeProblem != null ? routeProblem : inputs.routes.verified
                    ? "计划[" + issue.getIssueNo() + "]未绑定可生产的精确工程版本"
                    : "MDM工艺路线服务不可用，资源与工序无法核验";
            String status = inputs.routes.verified ? SchedulingOperationStatusEnum.ROUTE_MISSING.getCode()
                    : SchedulingOperationStatusEnum.RESOURCE_UNVERIFIED.getCode();
            return PlanOutcome.blocked(issue.getId(), status, summary);
        }
        if (!Objects.equals(issue.getFactoryCode(), route.getFactoryCode()))
            return PlanOutcome.blocked(issue.getId(), SchedulingOperationStatusEnum.ROUTE_GRAPH_INVALID.getCode(),
                    "已批准工程路线与计划工厂不一致");
        List<MdmProcessRouteWorkingVO> workings = Optional.ofNullable(route.getProcedureList()).orElse(List.of())
                .stream().sorted(Comparator.comparing(item -> Optional.ofNullable(item.getOrderNum()).orElse(Integer.MAX_VALUE)))
                .toList();
        if (workings.isEmpty()) {
            return PlanOutcome.blocked(issue.getId(), SchedulingOperationStatusEnum.OPERATION_MISSING.getCode(),
                    "工艺路线[" + route.getRouteCode() + "]尚未配置工序");
        }
        RouteGraph routeGraph;
        try {
            routeGraph = resolveRouteGraph(route, workings);
            workings = routeGraph.ordered();
        } catch (CheckedException ex) {
            return PlanOutcome.blocked(issue.getId(), SchedulingOperationStatusEnum.ROUTE_GRAPH_INVALID.getCode(),
                    "工艺路线[" + route.getRouteCode() + "]关系无效：" + ex.getMessage());
        }

        Map<String, PlnSchedulingOperationEntity> preferredByNode = localPolicy == null
                || localPolicy.preferredOperations().isEmpty() ? Map.of()
                : PlnRouteNodeSchedulingSupport.operations(route, workings,
                localPolicy.preferredOperations());
        OperationMaterialCatalog materialConstraints = operationMaterialCatalog(issue);
        Map<String, PlnOperationMaterialConstraintDTO> materialByNode = PlnRouteNodeSchedulingSupport.materials(
                route.getRouteCode(), workings, materialConstraints.byOperation.values());
        if (Boolean.TRUE.equals(issue.getMaterialHardBlocked()) && materialConstraints.byOperation.isEmpty()) {
            return PlanOutcome.blocked(issue.getId(), SchedulingOperationStatusEnum.MATERIAL_CONSTRAINT_BLOCKED.getCode(),
                    StrUtil.blankToDefault(issue.getMaterialConstraintSummary(),
                            "逐层物料约束存在硬阻断，不能生成工序排程"));
        }
        LocalDateTime startTime = requestedStart == null ? LocalDate.now().atStartOfDay() : requestedStart;
        LocalDateTime issueMaterialTime = issue.getMaterialAvailableTime() != null
                ? issue.getMaterialAvailableTime()
                : issue.getMaterialAvailableDate() == null ? null
                : issue.getMaterialAvailableDate().atTime(23, 59);
        if (!materialConstraints.versioned && issueMaterialTime != null
                && issueMaterialTime.isAfter(startTime)) {
            startTime = issueMaterialTime;
        }
        LocalDateTime routeReady = inputs.calendar.nextWorkingInstant(startTime);
        if (routeReady == null) {
            return PlanOutcome.blocked(issue.getId(), SchedulingOperationStatusEnum.CALENDAR_EXHAUSTED.getCode(),
                    "工作日历在未来" + MAX_CALENDAR_DAYS + "天内没有可用班次");
        }

        List<PlnSchedulingOperationEntity> operations = new ArrayList<>();
        Map<String, PlnSchedulingOperationEntity> completedByNode = new HashMap<>();
        boolean fallbackUsed = false;
        for (int index = 0; index < workings.size(); index++) {
            com.kitex.mom.plan.service.SchedulingExecutionContext.check();
            MdmProcessRouteWorkingVO working = workings.get(index);
            String nodeCode = PlnRouteNodeSchedulingSupport.nodeCode(working.getId(), working.getRouteNodeCode());
            PlnOperationMaterialConstraintDTO operationMaterial = materialByNode.get(nodeCode);
            LocalDateTime predecessorReady = routeReady;
            for (String predecessorCode : routeGraph.predecessors().getOrDefault(
                    nodeCode, List.of())) {
                PlnSchedulingOperationEntity predecessor = completedByNode.get(predecessorCode);
                if (predecessor == null || predecessor.getScheduledEndTime() == null) {
                    return PlanOutcome.blocked(issue.getId(), SchedulingOperationStatusEnum.ROUTE_GRAPH_INVALID.getCode(),
                            "工序[" + working.getProcessCode() + "]的前置工序[" + predecessorCode + "]没有有效排程结果",
                            operations);
                }
                LocalDateTime ready = Optional.ofNullable(predecessor.getTransferReadyTime()).orElseGet(() ->
                        predecessor.getScheduledEndTime().plusMinutes(
                                Optional.ofNullable(predecessor.getTransferMinutes()).orElse(0L)));
                if (ready.isAfter(predecessorReady)) {
                    predecessorReady = ready;
                }
            }
            if (operationMaterial != null && Boolean.TRUE.equals(operationMaterial.getHardBlocked())) {
                PlnSchedulingOperationEntity blocked = baseOperation(taskId, issue, route, working, index + 1);
                applyOperationMaterial(blocked, operationMaterial, issue.getMaterialLineageFingerprint());
                blocked.setScheduleStatus(SchedulingOperationStatusEnum.MATERIAL_CONSTRAINT_BLOCKED.getCode());
                blocked.setHardBlocked(true);
                blocked.setConstraintSummary("工序[" + working.getProcessCode() + "]"
                        + StrUtil.blankToDefault(operationMaterial.getSummary(), "投料存在硬阻断"));
                operations.add(blocked);
                return PlanOutcome.blocked(issue.getId(), blocked.getScheduleStatus(),
                        blocked.getConstraintSummary(), operations);
            }
            MdmProcedureEntity procedure = inputs.procedures.byCode.get(working.getProcessCode());
            if (procedure == null) {
                PlnSchedulingOperationEntity blocked = baseOperation(taskId, issue, route, working, index + 1);
                blocked.setScheduleStatus(inputs.procedures.verified
                        ? SchedulingOperationStatusEnum.DURATION_MISSING.getCode()
                        : SchedulingOperationStatusEnum.RESOURCE_UNVERIFIED.getCode());
                blocked.setHardBlocked(true);
                blocked.setConstraintSummary(inputs.procedures.verified
                        ? "工序[" + working.getProcessCode() + "]未找到启用的工序时间主数据"
                        : "MDM工序服务不可用，标准时间无法复核");
                operations.add(blocked);
                return PlanOutcome.blocked(issue.getId(), blocked.getScheduleStatus(), blocked.getConstraintSummary(), operations);
            }
            List<ResourceCandidate> candidates = resourceCandidates(route, working, procedure);
            if (candidates.isEmpty()) {
                PlnSchedulingOperationEntity blocked = baseOperation(taskId, issue, route, working, index + 1);
                blocked.setScheduleStatus(SchedulingOperationStatusEnum.RESOURCE_MISSING.getCode());
                blocked.setHardBlocked(true);
                blocked.setConstraintSummary("工序[" + working.getProcessCode() + "]未绑定启用工位，且路线没有可用线体或车间");
                operations.add(blocked);
                return PlanOutcome.blocked(issue.getId(), blocked.getScheduleStatus(), blocked.getConstraintSummary(), operations);
            }
            if (candidates.stream().anyMatch(ResourceCandidate::deviceBound) && !inputs.devices.verified) {
                PlnSchedulingOperationEntity blocked = baseOperation(taskId, issue, route, working, index + 1);
                blocked.setScheduleStatus(SchedulingOperationStatusEnum.DEVICE_UNVERIFIED.getCode());
                blocked.setHardBlocked(true);
                blocked.setConstraintSummary("工序[" + working.getProcessCode() + "]已绑定设备，但TPM设备可用性服务不可用");
                operations.add(blocked);
                return PlanOutcome.blocked(issue.getId(), blocked.getScheduleStatus(), blocked.getConstraintSummary(), operations);
            }
            boolean workforceRequired = inputs.workforce.requiresEmployee(working.getProcessCode());
            boolean workforceBound = candidates.stream().anyMatch(ResourceCandidate::employeeBound);
            if (workforceRequired && !workforceBound) {
                PlnSchedulingOperationEntity blocked = baseOperation(taskId, issue, route, working, index + 1);
                blocked.setScheduleStatus(SchedulingOperationStatusEnum.WORKFORCE_MISSING.getCode());
                blocked.setHardBlocked(true);
                blocked.setConstraintSummary("工序[" + working.getProcessCode()
                        + "]已配置技能要求，但候选工位未绑定执行员工");
                blocked.setCandidateResourceSnapshot(toJson(candidates));
                operations.add(blocked);
                return PlanOutcome.blocked(issue.getId(), blocked.getScheduleStatus(), blocked.getConstraintSummary(), operations);
            }
            if (workforceBound && !inputs.workforce.verified) {
                PlnSchedulingOperationEntity blocked = baseOperation(taskId, issue, route, working, index + 1);
                blocked.setScheduleStatus(SchedulingOperationStatusEnum.WORKFORCE_UNVERIFIED.getCode());
                blocked.setHardBlocked(true);
                blocked.setConstraintSummary("工序[" + working.getProcessCode()
                        + "]已绑定人员，但MDM人员能力与排班服务不可用");
                operations.add(blocked);
                return PlanOutcome.blocked(issue.getId(), blocked.getScheduleStatus(), blocked.getConstraintSummary(), operations);
            }
            if (workforceRequired) {
                candidates = expandWorkforceCandidates(candidates, inputs.workforce, working.getProcessCode());
            }
            boolean toolingRequired = inputs.tooling.requiresTooling(route.getRouteCode(), nodeCode);
            if (toolingRequired && !inputs.tooling.verified) {
                PlnSchedulingOperationEntity blocked = baseOperation(taskId, issue, route, working, index + 1);
                blocked.setScheduleStatus(SchedulingOperationStatusEnum.TOOLING_UNVERIFIED.getCode());
                blocked.setHardBlocked(true);
                blocked.setConstraintSummary("工序[" + working.getProcessCode()
                        + "]已配置工装要求，但MDM工装可用性服务不可用");
                operations.add(blocked);
                return PlanOutcome.blocked(issue.getId(), blocked.getScheduleStatus(), blocked.getConstraintSummary(), operations);
            }

            long offsetMinutes = toMinutes(procedure.getOffsetTimeHours(), "HOUR");
            LocalDateTime materialReady = constraintTime(operationMaterial);
            LocalDateTime earliestBoundary = predecessorReady.plusMinutes(offsetMinutes);
            if (materialReady != null && materialReady.isAfter(earliestBoundary)) earliestBoundary = materialReady;
            LocalDateTime earliest = inputs.calendar.nextWorkingInstant(earliestBoundary);
            AllocationChoice best = null;
            TransitionBlock changeoverBlock = null;
            boolean hasDuration = false;
            for (ResourceCandidate candidate : candidates) {
                com.kitex.mom.plan.service.SchedulingExecutionContext.check();
                if (workforceRequired && !candidate.employeeBound()) {
                    continue;
                }
                DurationParts duration = durationOf(issue.getNum(), procedure, candidate,
                        inputs.calendar.dailyMinutes(), inputs.durationCalibration);
                if (duration.activeMinutes <= 0) {
                    continue;
                }
                hasDuration = true;
                List<MdmWorkforceCandidateDTO> workforceCandidates = candidate.employeeBound()
                        ? inputs.workforce.getAll(candidate.employeeCode, working.getProcessCode()) : List.of();
                if (candidate.employeeBound() && (workforceCandidates.size() != candidate.employeeCodes().size()
                        || !workforceGroupQualified(workforceCandidates,
                        inputs.workforce.requirements(working.getProcessCode()), null, null))) {
                    continue;
                }
                WorkingCalendar candidateCalendar = candidate.employeeBound()
                        ? workforceCalendar(inputs.calendar, workforceCandidates,
                        inputs.workforce.requirements(working.getProcessCode())) : inputs.calendar;
                List<ToolingChoice> toolingChoices = buildToolingChoices(inputs.tooling, route.getRouteCode(),
                        nodeCode, candidate.deviceCode, issue.getNum(), duration,
                        toolingLifeReservations);
                for (ToolingChoice toolingChoice : toolingChoices) {
                    List<String> allocationKeys = new ArrayList<>(candidate.keys());
                    allocationKeys.addAll(toolingChoice.keys());
                    TransitionAllocation transitionAllocation = allocateWithChangeover(allocationKeys, candidate,
                            toolingChoice, earliest, duration, issue, schedulingFamily, working.getProcessCode(),
                            bookings, candidateCalendar, inputs.changeover);
                    if (transitionAllocation.allocation == null) {
                        if (transitionAllocation.block != null && (changeoverBlock == null
                                || SchedulingOperationStatusEnum.CHANGEOVER_FORBIDDEN.getCode().equals(
                                transitionAllocation.block.status()))) {
                            changeoverBlock = transitionAllocation.block;
                        }
                        continue;
                    }
                    DurationParts scheduledDuration = duration.withChangeover(transitionAllocation.transition.minutes);
                    AllocationChoice choice = new AllocationChoice(candidate, scheduledDuration,
                            transitionAllocation.allocation, toolingChoice, transitionAllocation.transition);
                    PlnSchedulingOperationEntity preferred = preferredByNode.get(nodeCode);
                    if (isBetterAllocation(choice, best, localPolicy, preferred)) {
                        best = choice;
                    }
                }
            }
            if (!hasDuration) {
                PlnSchedulingOperationEntity blocked = baseOperation(taskId, issue, route, working, index + 1);
                blocked.setScheduleStatus(SchedulingOperationStatusEnum.DURATION_MISSING.getCode());
                blocked.setHardBlocked(true);
                blocked.setConstraintSummary("工序[" + working.getProcessCode() + "]缺少工位产能、标准工时或工序加工时间");
                operations.add(blocked);
                return PlanOutcome.blocked(issue.getId(), blocked.getScheduleStatus(), blocked.getConstraintSummary(), operations);
            }
            if (best == null) {
                PlnSchedulingOperationEntity blocked = baseOperation(taskId, issue, route, working, index + 1);
                blocked.setSchedulingFamilyCode(schedulingFamily);
                blocked.setSchedulingFamilyName(inputs.changeover.familyName(schedulingFamily));
                if (changeoverBlock != null) {
                    blocked.setScheduleStatus(changeoverBlock.status());
                    blocked.setHardBlocked(true);
                    blocked.setConstraintSummary("工序[" + working.getProcessCode() + "]" + changeoverBlock.reason());
                    blocked.setCandidateResourceSnapshot(toJson(candidates));
                    blocked.setChangeoverRuleSnapshot(toJson(changeoverBlock));
                    operations.add(blocked);
                    return PlanOutcome.blocked(issue.getId(), blocked.getScheduleStatus(),
                            blocked.getConstraintSummary(), operations);
                }
                if (toolingRequired) {
                    ToolingBlock toolingBlock = toolingBlock(inputs.tooling, route.getRouteCode(),
                            nodeCode, candidates, issue.getNum(), procedure,
                            toolingLifeReservations, inputs.calendar.dailyMinutes(), inputs.durationCalibration);
                    blocked.setScheduleStatus(toolingBlock.status());
                    blocked.setHardBlocked(true);
                    blocked.setConstraintSummary("工序[" + working.getProcessCode() + "]" + toolingBlock.reason());
                    blocked.setCandidateResourceSnapshot(toJson(candidates));
                    blocked.setToolingAllocationSnapshot(toJson(inputs.tooling.candidates(
                            route.getRouteCode(), nodeCode)));
                    operations.add(blocked);
                    return PlanOutcome.blocked(issue.getId(), blocked.getScheduleStatus(), blocked.getConstraintSummary(), operations);
                }
                List<MdmWorkforceCandidateDTO> workforceCandidates = candidates.stream()
                        .filter(ResourceCandidate::employeeBound)
                        .flatMap(candidate -> inputs.workforce.getAll(candidate.employeeCode,
                                working.getProcessCode()).stream())
                        .collect(Collectors.toMap(MdmWorkforceCandidateDTO::getEmployeeCode, item -> item,
                                (a, b) -> a, LinkedHashMap::new)).values().stream().toList();
                boolean anyQualifiedEmployee = !candidates.isEmpty();
                boolean deviceBlocked = candidates.stream().filter(ResourceCandidate::deviceBound).anyMatch(candidate -> {
                    DurationParts duration = durationOf(issue.getNum(), procedure, candidate,
                            inputs.calendar.dailyMinutes(), inputs.durationCalibration);
                    return duration.activeMinutes > 0
                            && allocate(List.of(candidate.key()), earliest, duration.activeMinutes, bookings, inputs.calendar) != null;
                });
                boolean workforceBlocked = workforceBound || workforceRequired;
                SchedulingOperationStatusEnum blockedStatus = workforceBlocked
                        ? anyQualifiedEmployee ? SchedulingOperationStatusEnum.WORKFORCE_UNAVAILABLE
                        : SchedulingOperationStatusEnum.WORKFORCE_UNQUALIFIED
                        : deviceBlocked ? SchedulingOperationStatusEnum.DEVICE_UNAVAILABLE
                        : SchedulingOperationStatusEnum.CALENDAR_EXHAUSTED;
                blocked.setScheduleStatus(blockedStatus.getCode());
                blocked.setHardBlocked(true);
                blocked.setConstraintSummary("工序[" + working.getProcessCode() + "]在未来" + MAX_CALENDAR_DAYS
                        + "天内无法找到可执行" + (workforceBlocked ? "人员在岗/资质" : deviceBlocked ? "设备" : "资源") + "窗口"
                        + (workforceBlocked ? "；" + workforceBlockingSummary(workforceCandidates)
                        : deviceBlocked ? "；" + deviceBlockingSummary(candidates, inputs.devices) : ""));
                blocked.setCandidateResourceSnapshot(toJson(candidates));
                if (deviceBlocked) {
                    blocked.setDeviceAvailabilitySnapshot(deviceCandidatesSnapshot(candidates, inputs.devices));
                }
                if (workforceBlocked) {
                    blocked.setWorkforceAvailabilitySnapshot(toJson(workforceCandidates));
                }
                operations.add(blocked);
                return PlanOutcome.blocked(issue.getId(), blocked.getScheduleStatus(), blocked.getConstraintSummary(), operations);
            }

            PlnSchedulingOperationEntity operation = baseOperation(taskId, issue, route, working, index + 1);
            applyOperationMaterial(operation, operationMaterial, issue.getMaterialLineageFingerprint());
            operation.setSchedulingFamilyCode(schedulingFamily);
            operation.setSchedulingFamilyName(inputs.changeover.familyName(schedulingFamily));
            operation.setResourceType(best.candidate.type);
            operation.setResourceCode(best.candidate.code);
            operation.setResourceName(best.candidate.name);
            operation.setResourceSlotNo(best.candidate.slotNo);
            operation.setResourceGroupCode(best.candidate.resourceGroupCode);
            operation.setResourceGroupName(best.candidate.resourceGroupName);
            operation.setResourceGroupCapacity(best.candidate.resourceGroupCapacity);
            operation.setResourceGroupSlotNo(best.candidate.resourceGroupSlotNo);
            operation.setCandidateResourceSnapshot(toJson(candidates));
            operation.setDeviceCode(best.candidate.deviceCode);
            operation.setDeviceName(best.candidate.deviceName);
            operation.setDeviceAvailabilitySnapshot(deviceSnapshot(inputs.devices, best.candidate.deviceCode,
                    best.allocation.start, best.allocation.end));
            operation.setEmployeeCode(best.candidate.employeeCode);
            operation.setEmployeeName(best.candidate.employeeName);
            operation.setWorkforceAvailabilitySnapshot(workforceSnapshot(inputs.workforce,
                    best.candidate.employeeCode, working.getProcessCode(), best.allocation.start, best.allocation.end));
            operation.setToolingCodes(best.toolingChoice.codes());
            operation.setToolingNames(best.toolingChoice.names());
            operation.setToolingAllocationSnapshot(toolingSnapshot(inputs.tooling, route.getRouteCode(),
                    nodeCode, best.toolingChoice, best.allocation.start, best.allocation.end));
            operation.setToolingLifeUsageSnapshot(toJson(best.toolingChoice.lifeUsage()));
            operation.setSetupMinutes(best.duration.setupMinutes);
            operation.setChangeoverMinutes(best.transition.minutes);
            operation.setPrecedingFamilyCode(String.join(",", best.transition.precedingFamilies));
            operation.setPrecedingProductCode(String.join(",", best.transition.precedingProducts));
            operation.setChangeoverRuleSnapshot(toJson(best.transition));
            operation.setRunMinutes(best.duration.runMinutes);
            operation.setDisassemblyMinutes(best.duration.disassemblyMinutes);
            DurationCalibrationResolution durationCalibration = inputs.durationCalibration.resolve(
                    working.getProcessCode(), best.candidate);
            if (durationCalibration.profile != null) {
                operation.setDurationCalibrationProfileId(durationCalibration.profile.getId());
                operation.setDurationCalibrationVersionNo(durationCalibration.profile.getVersionNo());
                operation.setDurationCalibrationFingerprint(durationProfileFingerprint(durationCalibration.profile));
                operation.setDurationCalibrationSnapshot(toJson(Map.of(
                        "catalogFingerprint", inputs.durationCalibration.fingerprint,
                        "profile", durationCalibration.profile,
                        "precedence", durationCalibration.precedence)));
            }
            operation.setTransferMinutes(positiveMinutes(working.getTimeVal()));
            operation.setOverlapEnabled(Boolean.TRUE.equals(working.getOverlapEnabled()));
            operation.setTransferBatchQuantity(Boolean.TRUE.equals(working.getOverlapEnabled())
                    ? working.getTransferBatchQuantity() : null);
            operation.setActiveMinutes(best.duration.activeMinutes);
            operation.setScheduledStartTime(best.allocation.start);
            operation.setScheduledEndTime(best.allocation.end);
            WorkingCalendar releaseCalendar = inputs.calendar;
            if (best.candidate.employeeBound()) {
                releaseCalendar = workforceCalendar(inputs.calendar,
                        inputs.workforce.getAll(best.candidate.employeeCode, working.getProcessCode()),
                        inputs.workforce.requirements(working.getProcessCode()));
            }
            operation.setTransferReadyTime(calculateTransferReadyTime(operation, best.duration,
                    issue.getNum(), releaseCalendar));
            operation.setScheduleStatus(best.candidate.fallback
                    ? SchedulingOperationStatusEnum.SCHEDULED_FALLBACK_RESOURCE.getCode()
                    : SchedulingOperationStatusEnum.SCHEDULED.getCode());
            operation.setHardBlocked(false);
            operation.setConstraintSummary(best.candidate.fallback
                    ? "未配置可用工位，已按" + best.candidate.type + "共享有限产能排程"
                    : "按工位槽位[" + best.candidate.code + "#" + best.candidate.slotNo + "]"
                    + (best.candidate.groupBound() ? "+资源组[" + best.candidate.resourceGroupCode
                    + "#" + best.candidate.resourceGroupSlotNo + "]" : "")
                    + (best.candidate.deviceBound() ? "+设备[" + best.candidate.deviceCode + "]" : "")
                    + (best.candidate.employeeBound() ? "+人员[" + best.candidate.employeeCode + "]" : "")
                    + (best.toolingChoice.selected().isEmpty() ? "" : "+工装[" + best.toolingChoice.codes() + "]")
                    + (best.transition.minutes > 0 ? "+换型[" + best.transition.minutes + "分钟]" : "")
                    + (Boolean.TRUE.equals(operation.getOverlapEnabled()) ? "+转移批量["
                    + operation.getTransferBatchQuantity() + "]重叠" : "")
                    + "联合有限产能排程");
            operation.setSourceSnapshot(sourceSnapshot(procedure, best.candidate));
            PlnSchedulingOperationEntity original = preferredByNode.get(nodeCode);
            if (original != null && original.getSourceRouteNodeCode() == null) {
                // 历史正式工序只能经唯一原节点对应；新方案保留原合同，不能补造历史身份。
                operation.setRouteWorkingId(original.getRouteWorkingId());
                operation.setSourceRouteNodeCode(null);
                operation.setPredecessorNodeCodes(null);
            }
            operations.add(operation);
            completedByNode.put(nodeCode, operation);
            List<String> bookedKeys = new ArrayList<>(best.candidate.keys());
            bookedKeys.addAll(best.toolingChoice.keys());
            for (String key : bookedKeys) {
                bookings.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new BookedInterval(best.allocation.start, best.allocation.end, taskId, issue.getId(),
                                "APS_OPERATION", issue.getIssueNo(), issue.getProductCode(), schedulingFamily,
                                working.getProcessCode()));
                bookings.get(key).sort(Comparator.comparing(BookedInterval::start));
            }
            best.toolingChoice.lifeUsage().forEach((code, usage) ->
                    toolingLifeReservations.merge(code, usage, BigDecimal::add));
            fallbackUsed = fallbackUsed || best.candidate.fallback;
        }

        LocalDateTime first = firstStart(operations);
        LocalDateTime last = lastEnd(operations);
        String status = fallbackUsed ? SchedulingOperationStatusEnum.SCHEDULED_FALLBACK_RESOURCE.getCode()
                : SchedulingOperationStatusEnum.SCHEDULED.getCode();
        String resourceCount = String.valueOf(operations.stream().map(item -> item.getResourceType() + ":"
                + item.getResourceCode() + ":" + item.getResourceSlotNo()).distinct().count());
        String summary = operations.size() + "道工序已按" + resourceCount + "项资源有限产能排定，"
                + first + " 至 " + last + (fallbackUsed ? "；部分工序使用线体/车间保守资源" : "")
                + (operations.stream().anyMatch(item -> StrUtil.isNotBlank(item.getDeviceCode()))
                ? "；设备保养、维修及实时状态约束已纳入" : "")
                + (operations.stream().anyMatch(item -> StrUtil.isNotBlank(item.getEmployeeCode()))
                ? "；人员班次、技能、证书和占用约束已纳入" : "")
                + (operations.stream().anyMatch(item -> StrUtil.isNotBlank(item.getToolingCodes()))
                ? "；工装兼容、寿命、校准、维护和占用约束已纳入" : "")
                + (operations.stream().anyMatch(item -> Optional.ofNullable(item.getChangeoverMinutes()).orElse(0L) > 0)
                ? "；产品族顺序相关换型及资源占用已纳入" : "")
                + (routeGraph.parallel() ? "；并行/汇合工序已按DAG前置关系排定" : "");
        if (operations.stream().anyMatch(item -> item.getMaterialAvailableTime() != null)) {
            summary += "；逐工序投料汇合时刻已纳入开工下界";
        }
        boolean completionLate = issue.getDemandTime() != null && last != null
                && last.toLocalDate().isAfter(issue.getDemandTime());
        if (completionLate) {
            status = SchedulingOperationStatusEnum.COMPLETION_AFTER_DEMAND.getCode();
            summary += "；有限产能完工日" + last.toLocalDate() + "晚于需求日" + issue.getDemandTime();
            PlnSchedulingOperationEntity lastOperation = operations.get(operations.size() - 1);
            lastOperation.setScheduleStatus(status);
            lastOperation.setHardBlocked(true);
            lastOperation.setConstraintSummary(summary);
        }
        return new PlanOutcome(operations, new PlnOperationScheduleEvaluationDTO()
                .setIssueId(issue.getId()).setStatus(status).setHardBlocked(completionLate).setSummary(summary)
                .setOperationCount(operations.size()).setFirstStartTime(first).setLastEndTime(last));
    }

    private boolean isBetterAllocation(AllocationChoice choice, AllocationChoice current,
                                       LocalReplanChoicePolicy policy,
                                       PlnSchedulingOperationEntity preferred) {
        if (current == null) return true;
        String strategy = policy == null ? "RECOVERY_FIRST" : policy.strategy();
        if ("MINIMUM_DISRUPTION".equals(strategy)) {
            int choiceChanges = resourceChangeCount(choice, preferred);
            int currentChanges = resourceChangeCount(current, preferred);
            if (choiceChanges != currentChanges) return choiceChanges < currentChanges;
        }
        if ("CHANGEOVER_STABLE".equals(strategy)
                && choice.transition.minutes != current.transition.minutes) {
            return choice.transition.minutes < current.transition.minutes;
        }
        if (!choice.allocation.end.equals(current.allocation.end)) {
            return choice.allocation.end.isBefore(current.allocation.end);
        }
        if (choice.transition.minutes != current.transition.minutes) {
            return choice.transition.minutes < current.transition.minutes;
        }
        return choice.candidate.priority < current.candidate.priority;
    }

    private int resourceChangeCount(AllocationChoice choice, PlnSchedulingOperationEntity preferred) {
        if (preferred == null) return 4;
        int changes = 0;
        if (!Objects.equals(choice.candidate.code, preferred.getResourceCode())) changes++;
        if (!Objects.equals(choice.candidate.deviceCode, preferred.getDeviceCode())) changes++;
        if (!Objects.equals(choice.candidate.employeeCode, preferred.getEmployeeCode())) changes++;
        if (!Objects.equals(choice.toolingChoice.codes(), StrUtil.blankToDefault(preferred.getToolingCodes(), "")))
            changes++;
        return changes;
    }

    private ValidationResult validateOperations(PlnSchedulingTaskDetailEntity detail,
                                                List<PlnSchedulingOperationEntity> operations,
                                                List<PlnSchedulingOperationEntity> sameTask,
                                                List<PlnSchedulingOperationEntity> lockedOthers,
                                                RouteCatalog routes, ProcedureCatalog procedures,
                                                DeviceAvailabilityCatalog devices,
                                                WorkforceAvailabilityCatalog workforce,
                                                ToolingAvailabilityCatalog tooling,
                                                ChangeoverCatalog changeover,
                                                DurationCalibrationCatalog durationCalibration,
                                                Map<String, BigDecimal> toolingLifeReservations) {
        if (operations.isEmpty()) {
            return ValidationResult.blocked(SchedulingOperationStatusEnum.OPERATION_SNAPSHOT_MISSING.getCode(),
                    "计划没有工序级排程快照，必须重新执行排产");
        }
        Optional<PlnSchedulingOperationEntity> sourceBlocked = operations.stream()
                .filter(item -> Boolean.TRUE.equals(item.getHardBlocked()))
                .filter(item -> !isValidationStatus(item.getScheduleStatus())).findFirst();
        if (sourceBlocked.isPresent()) {
            return ValidationResult.blocked(sourceBlocked.get().getScheduleStatus(), sourceBlocked.get().getConstraintSummary());
        }
        OperationMaterialCatalog latestMaterial = operationMaterialCatalog(
                detail.getOperationMaterialConstraintSnapshot());
        if (Boolean.TRUE.equals(detail.getMaterialHardBlocked())) {
            String summary = StrUtil.blankToDefault(detail.getMaterialConstraintSummary(),
                    "确认锁定时逐层物料约束存在硬阻断");
            markValidationError(operations.get(0), SchedulingOperationStatusEnum.MATERIAL_CONSTRAINT_BLOCKED, summary);
            return ValidationResult.blocked(SchedulingOperationStatusEnum.MATERIAL_CONSTRAINT_BLOCKED.getCode(), summary);
        }
        if (!routes.verified || !procedures.verified) {
            markValidationError(operations.get(0), SchedulingOperationStatusEnum.RESOURCE_UNVERIFIED,
                    "确认锁定时无法实时读取MDM路线、工序或工位状态");
            return ValidationResult.blocked(SchedulingOperationStatusEnum.RESOURCE_UNVERIFIED.getCode(),
                    "确认锁定时无法实时读取MDM路线、工序或工位状态");
        }
        if (!durationCalibration.verified) {
            markValidationError(operations.get(0), SchedulingOperationStatusEnum.RESOURCE_UNVERIFIED,
                    "确认锁定时无法实时读取MDM实际工时校准目录");
            return ValidationResult.blocked(SchedulingOperationStatusEnum.RESOURCE_UNVERIFIED.getCode(),
                    "确认锁定时无法实时读取MDM实际工时校准目录");
        }
        if (!tooling.verified) {
            String summary = "确认锁定时无法实时复核工装要求及节点归属";
            markValidationError(operations.get(0), SchedulingOperationStatusEnum.TOOLING_UNVERIFIED, summary);
            return ValidationResult.blocked(SchedulingOperationStatusEnum.TOOLING_UNVERIFIED.getCode(), summary);
        }
        if (!changeover.verified) {
            markValidationError(operations.get(0), SchedulingOperationStatusEnum.CHANGEOVER_UNVERIFIED,
                    "确认锁定时无法实时读取产品族与换型规则");
            return ValidationResult.blocked(SchedulingOperationStatusEnum.CHANGEOVER_UNVERIFIED.getCode(),
                    "确认锁定时无法实时读取产品族与换型规则");
        }
        String currentFamily = changeover.family(detail.getProductCode());
        if (changeover.governanceRequired && StrUtil.isBlank(currentFamily)) {
            markValidationError(operations.get(0), SchedulingOperationStatusEnum.CHANGEOVER_FAMILY_MISSING,
                    "产品未归属启用的APS排产产品族");
            return ValidationResult.blocked(SchedulingOperationStatusEnum.CHANGEOVER_FAMILY_MISSING.getCode(),
                    "产品未归属启用的APS排产产品族，禁止锁定");
        }
        Map<String, List<BookedInterval>> changeoverBookings = operationBookings(
                java.util.stream.Stream.concat(sameTask.stream(), lockedOthers.stream()).distinct().toList());
        String routeProblem = routes.failures.get(routeKey(detail));
        MdmProcessRouteDTO route = routes.byIssue.get(routeKey(detail));
        if (route == null) {
            markValidationError(operations.get(0), SchedulingOperationStatusEnum.RESOURCE_INVALID,
                    routeProblem == null ? "精确工程版本已不可生产" : routeProblem);
            return ValidationResult.blocked(SchedulingOperationStatusEnum.RESOURCE_INVALID.getCode(),
                    (routeProblem == null ? "精确工程版本已不可生产" : routeProblem) + "，必须重新排产");
        }
        List<MdmProcessRouteWorkingVO> routeWorkings = Optional.ofNullable(route.getProcedureList()).orElse(List.of());
        if (!Objects.equals(detail.getFactoryCode(), route.getFactoryCode())) {
            String summary = "当前工艺路线与排程明细工厂不一致";
            markValidationError(operations.get(0), SchedulingOperationStatusEnum.ROUTE_GRAPH_INVALID, summary);
            return ValidationResult.blocked(SchedulingOperationStatusEnum.ROUTE_GRAPH_INVALID.getCode(), summary);
        }
        RouteGraph routeGraph;
        try {
            routeGraph = resolveRouteGraph(route, routeWorkings);
            routeWorkings = routeGraph.ordered();
        } catch (CheckedException ex) {
            String summary = "当前工艺路线DAG关系无效：" + ex.getMessage();
            markValidationError(operations.get(0), SchedulingOperationStatusEnum.ROUTE_GRAPH_INVALID, summary);
            return ValidationResult.blocked(SchedulingOperationStatusEnum.ROUTE_GRAPH_INVALID.getCode(), summary);
        }
        Map<String, PlnSchedulingOperationEntity> operationByNode;
        Map<String, PlnOperationMaterialConstraintDTO> materialByNode;
        try {
            operationByNode = PlnRouteNodeSchedulingSupport.operations(route, routeWorkings, operations);
            materialByNode = PlnRouteNodeSchedulingSupport.materials(route.getRouteCode(), routeWorkings,
                    latestMaterial.byOperation.values());
        } catch (CheckedException ex) {
            String summary = "工序排程或投料节点不再对应当前路线：" + ex.getMessage();
            markValidationError(operations.get(0), SchedulingOperationStatusEnum.ROUTE_GRAPH_INVALID, summary);
            return ValidationResult.blocked(SchedulingOperationStatusEnum.ROUTE_GRAPH_INVALID.getCode(), summary);
        }
        for (PlnSchedulingOperationEntity operation : operations) {
            resetValidationStatus(operation);
            MdmProcessRouteWorkingVO working = PlnRouteNodeSchedulingSupport.working(routeWorkings,
                    operation.getSourceRouteNodeCode(), operation.getProcessCode(), operation.getRouteWorkingId());
            String nodeCode = working.getRouteNodeCode();
            if (!Objects.equals(operation.getRouteId(), route.getId())) {
                String summary = "工序排程的原路线ID已变化，必须重新排产";
                markValidationError(operation, SchedulingOperationStatusEnum.ROUTE_GRAPH_INVALID, summary);
                return ValidationResult.blocked(SchedulingOperationStatusEnum.ROUTE_GRAPH_INVALID.getCode(), summary);
            }
            if (!Objects.equals(operation.getMaterialLineageFingerprint(),
                    detail.getMaterialLineageFingerprint())) {
                String summary = "工序[" + operation.getProcessCode()
                        + "]使用的BOM路径指纹与最新MRP不一致，必须重新排产";
                markValidationError(operation, SchedulingOperationStatusEnum.MATERIAL_CONSTRAINT_CHANGED, summary);
                return ValidationResult.blocked(SchedulingOperationStatusEnum.MATERIAL_CONSTRAINT_CHANGED.getCode(), summary);
            }
            PlnOperationMaterialConstraintDTO currentMaterial = materialByNode.get(nodeCode);
            String currentMaterialFingerprint = currentMaterial == null ? null
                    : currentMaterial.getEvidenceFingerprint();
            if (!Objects.equals(operation.getMaterialConstraintFingerprint(), currentMaterialFingerprint)) {
                String summary = "工序[" + operation.getProcessCode()
                        + "]的投料映射、供应时刻或BOM路径证据已变化，必须重新排产";
                markValidationError(operation, SchedulingOperationStatusEnum.MATERIAL_CONSTRAINT_CHANGED, summary);
                return ValidationResult.blocked(SchedulingOperationStatusEnum.MATERIAL_CONSTRAINT_CHANGED.getCode(), summary);
            }
            if (currentMaterial != null && Boolean.TRUE.equals(currentMaterial.getHardBlocked())) {
                String summary = "工序[" + operation.getProcessCode() + "]"
                        + StrUtil.blankToDefault(currentMaterial.getSummary(), "投料存在硬阻断");
                markValidationError(operation, SchedulingOperationStatusEnum.MATERIAL_CONSTRAINT_BLOCKED, summary);
                return ValidationResult.blocked(SchedulingOperationStatusEnum.MATERIAL_CONSTRAINT_BLOCKED.getCode(), summary);
            }
            if (!Objects.equals(currentFamily, operation.getSchedulingFamilyCode())) {
                String summary = "产品排产族已由[" + operation.getSchedulingFamilyCode() + "]变更为["
                        + currentFamily + "]，必须重新排产";
                markValidationError(operation, SchedulingOperationStatusEnum.CHANGEOVER_RULE_CHANGED, summary);
                return ValidationResult.blocked(SchedulingOperationStatusEnum.CHANGEOVER_RULE_CHANGED.getCode(), summary);
            }
            if (operation.getScheduledStartTime() == null || operation.getScheduledEndTime() == null
                    || !operation.getScheduledEndTime().isAfter(operation.getScheduledStartTime())
                    || operation.getActiveMinutes() == null || operation.getActiveMinutes() <= 0) {
                String summary = "工序[" + operation.getProcessCode() + "]缺少合法的开始、结束或有效工时快照";
                markValidationError(operation, SchedulingOperationStatusEnum.OPERATION_TIME_INVALID, summary);
                return ValidationResult.blocked(SchedulingOperationStatusEnum.OPERATION_TIME_INVALID.getCode(), summary);
            }
            LocalDateTime currentMaterialTime = constraintTime(currentMaterial);
            if (currentMaterialTime != null && operation.getScheduledStartTime().isBefore(currentMaterialTime)) {
                String summary = "工序[" + operation.getProcessCode() + "]计划开始时间早于其"
                        + currentMaterial.getMaterialCodes().size() + "项投料汇合时刻";
                markValidationError(operation, SchedulingOperationStatusEnum.MATERIAL_CONSTRAINT_CHANGED, summary);
                return ValidationResult.blocked(SchedulingOperationStatusEnum.MATERIAL_CONSTRAINT_CHANGED.getCode(), summary);
            }
            LocalDateTime latestTransferReady = operation.getScheduledEndTime().plusMinutes(
                    Optional.ofNullable(operation.getTransferMinutes()).orElse(0L));
            if (operation.getTransferReadyTime() == null
                    || operation.getTransferReadyTime().isBefore(operation.getScheduledStartTime())
                    || operation.getTransferReadyTime().isAfter(latestTransferReady)
                    || (!Boolean.TRUE.equals(operation.getOverlapEnabled())
                    && !operation.getTransferReadyTime().equals(latestTransferReady))) {
                String summary = "工序[" + operation.getProcessCode() + "]转移批量放行时间快照无效";
                markValidationError(operation, SchedulingOperationStatusEnum.OPERATION_TIME_INVALID, summary);
                return ValidationResult.blocked(SchedulingOperationStatusEnum.OPERATION_TIME_INVALID.getCode(), summary);
            }
            boolean nodeContract = operation.getSourceRouteNodeCode() != null;
            Set<String> savedPredecessors = csvSet(nodeContract
                    ? operation.getPredecessorNodeCodes() : operation.getPredecessorCodes());
            Set<String> currentPredecessors = new LinkedHashSet<>(routeGraph.predecessors()
                    .getOrDefault(nodeCode, List.of()));
            Set<String> currentProcessPredecessors = currentPredecessors.stream()
                    .map(code -> operationByNode.get(code).getProcessCode())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (nodeContract && operation.getPredecessorNodeCodes() == null
                    || !nodeContract && operation.getPredecessorNodeCodes() != null
                    || !savedPredecessors.equals(nodeContract ? currentPredecessors : currentProcessPredecessors)
                    || !csvSet(operation.getPredecessorCodes()).equals(currentProcessPredecessors)) {
                String summary = "工序[" + operation.getProcessCode() + "]前置关系已变化，排程快照["
                        + String.join(",", savedPredecessors) + "]、当前路线["
                        + String.join(",", currentPredecessors) + "]，必须重新排产";
                markValidationError(operation, SchedulingOperationStatusEnum.ROUTE_GRAPH_INVALID, summary);
                return ValidationResult.blocked(SchedulingOperationStatusEnum.ROUTE_GRAPH_INVALID.getCode(), summary);
            }
            for (String predecessorCode : currentPredecessors) {
                PlnSchedulingOperationEntity predecessor = operationByNode.get(predecessorCode);
                if (predecessor == null || predecessor.getScheduledEndTime() == null || predecessor.getTransferReadyTime() == null) {
                    String summary = "工序[" + operation.getProcessCode() + "]缺少前置工序["
                            + predecessorCode + "]的有效排程快照";
                    markValidationError(operation, SchedulingOperationStatusEnum.PRECEDENCE_INVALID, summary);
                    return ValidationResult.blocked(SchedulingOperationStatusEnum.PRECEDENCE_INVALID.getCode(), summary);
                }
                LocalDateTime requiredStart = predecessor.getTransferReadyTime();
                if (operation.getScheduledStartTime().isBefore(requiredStart)) {
                    String summary = "工序[" + operation.getProcessCode() + "]开始时间早于前序工序["
                            + predecessor.getProcessCode() + "]完工及转运时间";
                    markValidationError(operation, SchedulingOperationStatusEnum.PRECEDENCE_INVALID, summary);
                    return ValidationResult.blocked(SchedulingOperationStatusEnum.PRECEDENCE_INVALID.getCode(), summary);
                }
            }
            boolean currentOverlap = working != null && Boolean.TRUE.equals(working.getOverlapEnabled());
            BigDecimal currentTransferBatch = currentOverlap ? working.getTransferBatchQuantity() : null;
            if (currentOverlap != Boolean.TRUE.equals(operation.getOverlapEnabled())
                    || !sameDecimal(currentTransferBatch, operation.getTransferBatchQuantity())) {
                String summary = "工序[" + operation.getProcessCode() + "]转移批量或重叠策略已变化，必须重新排产";
                markValidationError(operation, SchedulingOperationStatusEnum.ROUTE_GRAPH_INVALID, summary);
                return ValidationResult.blocked(SchedulingOperationStatusEnum.ROUTE_GRAPH_INVALID.getCode(), summary);
            }
            MdmProcedureEntity procedure = procedures.byCode.get(operation.getProcessCode());
            List<ResourceCandidate> currentCandidates = working == null || procedure == null ? List.of()
                    : resourceCandidates(route, working, procedure);
            if (!resourceBindingValid(operation, currentCandidates)) {
                String summary = "工序[" + operation.getProcessCode() + "]已分配的资源组合["
                        + operation.getResourceCode() + (StrUtil.isBlank(operation.getDeviceCode()) ? ""
                        : "/" + operation.getDeviceCode())
                        + (operation.getResourceSlotNo() == null ? "" : "#槽位" + operation.getResourceSlotNo())
                        + (StrUtil.isBlank(operation.getResourceGroupCode()) ? "" : "/资源组"
                        + operation.getResourceGroupCode() + "#" + operation.getResourceGroupSlotNo())
                        + "]不再属于启用候选资源或已超过当前并行能力";
                SchedulingOperationStatusEnum invalidStatus = StrUtil.isNotBlank(operation.getEmployeeCode())
                        ? SchedulingOperationStatusEnum.WORKFORCE_BINDING_INVALID
                        : StrUtil.isNotBlank(operation.getDeviceCode())
                        ? SchedulingOperationStatusEnum.DEVICE_BINDING_INVALID : SchedulingOperationStatusEnum.RESOURCE_INVALID;
                markValidationError(operation, invalidStatus, summary);
                return ValidationResult.blocked(invalidStatus.getCode(), summary);
            }
            ResourceCandidate currentResource = selectedResourceCandidate(operation, currentCandidates);
            DurationCalibrationResolution latestDuration = durationCalibration.resolve(operation.getProcessCode(),
                    currentResource);
            Long latestProfileId = latestDuration.profile == null ? null : latestDuration.profile.getId();
            Integer latestProfileVersion = latestDuration.profile == null ? null : latestDuration.profile.getVersionNo();
            String latestEvidence = latestDuration.profile == null ? null : durationProfileFingerprint(latestDuration.profile);
            if (!Objects.equals(operation.getDurationCalibrationProfileId(), latestProfileId)
                    || !Objects.equals(operation.getDurationCalibrationVersionNo(), latestProfileVersion)
                    || !Objects.equals(operation.getDurationCalibrationFingerprint(), latestEvidence)) {
                String summary = "工序[" + operation.getProcessCode() + "]实际工时校准版本已变化，排程采用["
                        + Optional.ofNullable(operation.getDurationCalibrationProfileId()).map(String::valueOf)
                        .orElse("原主数据") + "]，当前生效["
                        + Optional.ofNullable(latestProfileId).map(String::valueOf).orElse("原主数据")
                        + "]，必须重新排产";
                markValidationError(operation, SchedulingOperationStatusEnum.OPERATION_TIME_INVALID, summary);
                return ValidationResult.blocked(SchedulingOperationStatusEnum.OPERATION_TIME_INVALID.getCode(), summary);
            }
            if (StrUtil.isNotBlank(operation.getDeviceCode())) {
                if (!devices.verified) {
                    String summary = "确认锁定时无法实时复核设备[" + operation.getDeviceCode() + "]的TPM可用性";
                    markValidationError(operation, SchedulingOperationStatusEnum.DEVICE_UNVERIFIED, summary);
                    return ValidationResult.blocked(SchedulingOperationStatusEnum.DEVICE_UNVERIFIED.getCode(), summary);
                }
                Optional<TpmDeviceUnavailableWindowDTO> unavailable = devices.byDevice
                        .getOrDefault(operation.getDeviceCode(), List.of()).stream()
                        .filter(window -> overlaps(operation, window)).findFirst();
                if (unavailable.isPresent()) {
                    TpmDeviceUnavailableWindowDTO window = unavailable.get();
                    String summary = "设备[" + operation.getDeviceCode() + "]在工序计划时间内不可用："
                            + window.getReason() + "（" + window.getStartTime() + "~"
                            + (window.getEndTime() == null ? "未结束" : window.getEndTime()) + "）";
                    markValidationError(operation, SchedulingOperationStatusEnum.DEVICE_UNAVAILABLE, summary);
                    return ValidationResult.blocked(SchedulingOperationStatusEnum.DEVICE_UNAVAILABLE.getCode(), summary);
                }
            }
            if (StrUtil.isNotBlank(operation.getEmployeeCode())) {
                if (!workforce.verified) {
                    String summary = "确认锁定时无法实时复核人员[" + operation.getEmployeeCode() + "]的能力与排班";
                    markValidationError(operation, SchedulingOperationStatusEnum.WORKFORCE_UNVERIFIED, summary);
                    return ValidationResult.blocked(SchedulingOperationStatusEnum.WORKFORCE_UNVERIFIED.getCode(), summary);
                }
                List<MdmWorkforceCandidateDTO> selectedWorkers = workforce.getAll(
                        operation.getEmployeeCode(), operation.getProcessCode());
                List<String> selectedCodes = csvList(operation.getEmployeeCode());
                if (selectedWorkers.size() != selectedCodes.size() || !workforceGroupQualified(selectedWorkers,
                        workforce.requirements(operation.getProcessCode()), operation.getScheduledStartTime(),
                        operation.getScheduledEndTime())) {
                    String summary = "人员组合[" + operation.getEmployeeCode() + "]不再满足工序["
                            + operation.getProcessCode() + "]的技能人数、等级、证书或有效期要求："
                            + workforceGroupGap(selectedWorkers, workforce.requirements(operation.getProcessCode()),
                            operation.getScheduledStartTime(), operation.getScheduledEndTime());
                    markValidationError(operation, SchedulingOperationStatusEnum.WORKFORCE_UNQUALIFIED, summary);
                    return ValidationResult.blocked(SchedulingOperationStatusEnum.WORKFORCE_UNQUALIFIED.getCode(), summary);
                }
                WorkingCalendar personCalendar = workforceCalendar(new WorkingCalendar(
                                workforceWindowUnion(selectedWorkers), 480), selectedWorkers,
                        workforce.requirements(operation.getProcessCode()));
                LocalDateTime verifiedEnd = personCalendar.addWorkingMinutes(operation.getScheduledStartTime(),
                        operation.getActiveMinutes());
                if (!Objects.equals(verifiedEnd, operation.getScheduledEndTime())) {
                    String summary = "人员组合[" + operation.getEmployeeCode() + "]的最新共同班次/请假/培训/加班日历"
                            + "已不能覆盖工序计划工时";
                    markValidationError(operation, SchedulingOperationStatusEnum.WORKFORCE_UNAVAILABLE, summary);
                    return ValidationResult.blocked(SchedulingOperationStatusEnum.WORKFORCE_UNAVAILABLE.getCode(), summary);
                }
            }
            List<MdmProcedureToolingRequirementEntity> toolingRequirements = tooling.requirements(
                            operation.getRouteCode(), nodeCode).stream()
                    .filter(item -> !Boolean.FALSE.equals(item.getMandatory())).toList();
            if (!toolingRequirements.isEmpty()) {
                if (!tooling.verified) {
                    String summary = "确认锁定时无法实时复核工装要求和可用性";
                    markValidationError(operation, SchedulingOperationStatusEnum.TOOLING_UNVERIFIED, summary);
                    return ValidationResult.blocked(SchedulingOperationStatusEnum.TOOLING_UNVERIFIED.getCode(), summary);
                }
                ResourceCandidate selectedResource = new ResourceCandidate(operation.getResourceType(),
                        operation.getResourceCode(), operation.getResourceName(), 0, null, null,
                        !"STATION".equals(operation.getResourceType()), operation.getDeviceCode(),
                        operation.getDeviceName(), operation.getEmployeeCode(), operation.getEmployeeName(),
                        operation.getResourceSlotNo(), operation.getResourceGroupCode(),
                        operation.getResourceGroupName(), operation.getResourceGroupCapacity(),
                        operation.getResourceGroupSlotNo());
                DurationParts duration = new DurationParts(
                        Optional.ofNullable(operation.getSetupMinutes()).orElse(0L),
                        Optional.ofNullable(operation.getRunMinutes()).orElse(0L),
                        Optional.ofNullable(operation.getDisassemblyMinutes()).orElse(0L),
                        Optional.ofNullable(operation.getActiveMinutes()).orElse(0L));
                Set<String> selectedCodes = csvSet(operation.getToolingCodes());
                List<ToolingChoice> choices = buildToolingChoices(tooling, operation.getRouteCode(),
                        nodeCode, operation.getDeviceCode(), operation.getPlannedQuantity(),
                        duration, toolingLifeReservations);
                ToolingChoice matched = choices.stream()
                        .filter(choice -> selectedCodes.equals(new LinkedHashSet<>(choice.lifeUsage().keySet())))
                        .findFirst().orElse(null);
                if (matched == null) {
                    ToolingBlock block = toolingBlock(tooling, operation.getRouteCode(), nodeCode,
                            List.of(selectedResource), operation.getPlannedQuantity(), procedure,
                            toolingLifeReservations, 480, durationCalibration);
                    String summary = "工装[" + StrUtil.blankToDefault(operation.getToolingCodes(), "未选择")
                            + "]锁定复核失败：" + block.reason();
                    markValidationError(operation, SchedulingOperationStatusEnum.valueOf(block.status()), summary);
                    return ValidationResult.blocked(block.status(), summary);
                }
                Map<String, BigDecimal> savedUsage = parseLifeUsage(operation.getToolingLifeUsageSnapshot());
                if (!matched.lifeUsage().equals(savedUsage)) {
                    String summary = "工装寿命预占快照与最新工艺用量规则不一致，必须重新排产";
                    markValidationError(operation, SchedulingOperationStatusEnum.TOOLING_BINDING_INVALID, summary);
                    return ValidationResult.blocked(SchedulingOperationStatusEnum.TOOLING_BINDING_INVALID.getCode(), summary);
                }
                Optional<MdmToolingCandidateDTO> unavailable = matched.selected().stream()
                        .filter(candidate -> candidate.getUnavailableWindows().stream().anyMatch(window ->
                                window.getStartTime() != null && window.getEndTime() != null
                                        && operation.getScheduledEndTime().isAfter(window.getStartTime())
                                        && operation.getScheduledStartTime().isBefore(window.getEndTime())))
                        .findFirst();
                if (unavailable.isPresent()) {
                    String summary = "工装[" + unavailable.get().getToolingCode()
                            + "]在计划时间内处于维护、校准、维修或锁定窗口";
                    markValidationError(operation, SchedulingOperationStatusEnum.TOOLING_UNAVAILABLE, summary);
                    return ValidationResult.blocked(SchedulingOperationStatusEnum.TOOLING_UNAVAILABLE.getCode(), summary);
                }
                matched.lifeUsage().forEach((code, usage) ->
                        toolingLifeReservations.merge(code, usage, BigDecimal::add));
            } else if (StrUtil.isNotBlank(operation.getToolingCodes())) {
                String summary = "工序工装要求已移除或变更，旧工装分配不能继续锁定";
                markValidationError(operation, SchedulingOperationStatusEnum.TOOLING_BINDING_INVALID, summary);
                return ValidationResult.blocked(SchedulingOperationStatusEnum.TOOLING_BINDING_INVALID.getCode(), summary);
            }
            List<ScopeResource> changeoverScopes = new ArrayList<>();
            if ("STATION".equals(operation.getResourceType())) {
                changeoverScopes.add(new ScopeResource("STATION", operation.getResourceCode(), resourceKey(operation)));
            }
            if (StrUtil.isNotBlank(operation.getDeviceCode())) {
                changeoverScopes.add(new ScopeResource("DEVICE", operation.getDeviceCode(), deviceKey(operation.getDeviceCode())));
            }
            csvList(operation.getToolingCodes()).stream()
                    .map(code -> new ScopeResource("TOOLING", code, toolingKey(code))).forEach(changeoverScopes::add);
            TransitionResolution latestTransition = resolveChangeover(changeoverScopes,
                    operation.getScheduledStartTime(), operation.getProcessCode(), currentFamily,
                    changeoverBookings, changeover);
            if (latestTransition.block != null) {
                markValidationError(operation, SchedulingOperationStatusEnum.valueOf(latestTransition.block.status()),
                        latestTransition.block.reason());
                return ValidationResult.blocked(latestTransition.block.status(), latestTransition.block.reason());
            }
            long savedChangeover = Optional.ofNullable(operation.getChangeoverMinutes()).orElse(0L);
            String latestSnapshot = toJson(latestTransition.transition);
            if (savedChangeover != latestTransition.transition.minutes
                    || !Objects.equals(operation.getChangeoverRuleSnapshot(), latestSnapshot)) {
                String summary = "工序[" + operation.getProcessCode()
                        + "]的相邻产品族、换型规则版本或换型时长已变化，必须重新排产";
                markValidationError(operation, SchedulingOperationStatusEnum.CHANGEOVER_RULE_CHANGED, summary);
                return ValidationResult.blocked(SchedulingOperationStatusEnum.CHANGEOVER_RULE_CHANGED.getCode(), summary);
            }
            Optional<PlnSchedulingOperationEntity> overlap = lockedOthers.stream()
                    .filter(other -> sharesResource(operation, other))
                    .filter(other -> overlaps(operation, other)).findFirst();
            if (overlap.isEmpty()) {
                overlap = sameTask.stream().filter(other -> !Objects.equals(other.getId(), operation.getId()))
                        .filter(other -> sharesResource(operation, other))
                        .filter(other -> overlaps(operation, other)).findFirst();
            }
            if (overlap.isPresent()) {
                PlnSchedulingOperationEntity other = overlap.get();
                String resourceLabel = StrUtil.isNotBlank(operation.getEmployeeCode())
                        && !Collections.disjoint(csvSet(operation.getEmployeeCode()), csvSet(other.getEmployeeCode()))
                        ? "人员[" + operation.getEmployeeCode() + "]"
                        : StrUtil.isNotBlank(operation.getToolingCodes())
                        && !Collections.disjoint(csvSet(operation.getToolingCodes()), csvSet(other.getToolingCodes()))
                        ? "工装[" + operation.getToolingCodes() + "]"
                        : StrUtil.isNotBlank(operation.getDeviceCode())
                        && Objects.equals(operation.getDeviceCode(), other.getDeviceCode())
                        ? "设备[" + operation.getDeviceCode() + "]" : "资源[" + operation.getResourceCode() + "]";
                String summary = resourceLabel + "的工序[" + operation.getProcessCode() + "]与计划["
                        + other.getIssueNo() + "]在" + other.getScheduledStartTime() + "~" + other.getScheduledEndTime() + "重叠";
                markValidationError(operation, SchedulingOperationStatusEnum.RESOURCE_OVERLAP, summary);
                return ValidationResult.blocked(SchedulingOperationStatusEnum.RESOURCE_OVERLAP.getCode(), summary);
            }
        }
        LocalDateTime completion = lastEnd(operations);
        if (detail.getDemandTime() != null && completion != null
                && completion.toLocalDate().isAfter(detail.getDemandTime())) {
            PlnSchedulingOperationEntity lastOperation = operations.get(operations.size() - 1);
            String summary = "有限产能完工日" + completion.toLocalDate() + "晚于需求日" + detail.getDemandTime();
            markValidationError(lastOperation, SchedulingOperationStatusEnum.COMPLETION_AFTER_DEMAND, summary);
            return ValidationResult.blocked(SchedulingOperationStatusEnum.COMPLETION_AFTER_DEMAND.getCode(), summary);
        }
        boolean fallback = operations.stream().anyMatch(item -> !"STATION".equals(item.getResourceType()));
        return new ValidationResult(fallback ? SchedulingOperationStatusEnum.SCHEDULED_FALLBACK_RESOURCE.getCode()
                : SchedulingOperationStatusEnum.SCHEDULED.getCode(), false,
                "工艺DAG、工序资源槽位、共享资源组、设备、人员、工装寿命/校准/维护、时间占用和主数据实时复核通过");
    }

    private void markValidationError(PlnSchedulingOperationEntity operation,
                                     SchedulingOperationStatusEnum status, String summary) {
        operation.setScheduleStatus(status.getCode());
        operation.setHardBlocked(true);
        operation.setConstraintSummary(summary);
        if (operation.getId() != null && operation.getId() > 0) operationMapper.updateById(operation);
    }

    private void resetValidationStatus(PlnSchedulingOperationEntity operation) {
        if (!isValidationStatus(operation.getScheduleStatus())) {
            return;
        }
        operation.setScheduleStatus("STATION".equals(operation.getResourceType())
                ? SchedulingOperationStatusEnum.SCHEDULED.getCode()
                : SchedulingOperationStatusEnum.SCHEDULED_FALLBACK_RESOURCE.getCode());
        operation.setHardBlocked(false);
        operation.setConstraintSummary("STATION".equals(operation.getResourceType())
                ? "按工位" + (StrUtil.isNotBlank(operation.getDeviceCode())
                ? "+设备[" + operation.getDeviceCode() + "]" : "")
                + (StrUtil.isNotBlank(operation.getEmployeeCode())
                ? "+人员[" + operation.getEmployeeCode() + "]" : "")
                + (StrUtil.isNotBlank(operation.getToolingCodes())
                ? "+工装[" + operation.getToolingCodes() + "]" : "") + "联合有限产能排程"
                : "按共享资源保守有限产能排程");
        if (operation.getId() != null && operation.getId() > 0) operationMapper.updateById(operation);
    }

    private boolean isValidationStatus(String status) {
        return Set.of(SchedulingOperationStatusEnum.RESOURCE_OVERLAP.getCode(),
                SchedulingOperationStatusEnum.RESOURCE_INVALID.getCode(),
                SchedulingOperationStatusEnum.RESOURCE_UNVERIFIED.getCode(),
                SchedulingOperationStatusEnum.DEVICE_UNAVAILABLE.getCode(),
                SchedulingOperationStatusEnum.DEVICE_UNVERIFIED.getCode(),
                SchedulingOperationStatusEnum.DEVICE_BINDING_INVALID.getCode(),
                SchedulingOperationStatusEnum.WORKFORCE_MISSING.getCode(),
                SchedulingOperationStatusEnum.WORKFORCE_UNQUALIFIED.getCode(),
                SchedulingOperationStatusEnum.WORKFORCE_UNAVAILABLE.getCode(),
                SchedulingOperationStatusEnum.WORKFORCE_UNVERIFIED.getCode(),
                SchedulingOperationStatusEnum.WORKFORCE_BINDING_INVALID.getCode(),
                SchedulingOperationStatusEnum.TOOLING_MISSING.getCode(),
                SchedulingOperationStatusEnum.TOOLING_UNQUALIFIED.getCode(),
                SchedulingOperationStatusEnum.TOOLING_UNAVAILABLE.getCode(),
                SchedulingOperationStatusEnum.TOOLING_UNVERIFIED.getCode(),
                SchedulingOperationStatusEnum.TOOLING_BINDING_INVALID.getCode(),
                SchedulingOperationStatusEnum.TOOLING_LIFE_INSUFFICIENT.getCode(),
                SchedulingOperationStatusEnum.CHANGEOVER_FAMILY_MISSING.getCode(),
                SchedulingOperationStatusEnum.CHANGEOVER_RULE_MISSING.getCode(),
                SchedulingOperationStatusEnum.CHANGEOVER_FORBIDDEN.getCode(),
                SchedulingOperationStatusEnum.CHANGEOVER_UNVERIFIED.getCode(),
                SchedulingOperationStatusEnum.CHANGEOVER_RULE_CHANGED.getCode(),
                SchedulingOperationStatusEnum.MATERIAL_CONSTRAINT_CHANGED.getCode(),
                SchedulingOperationStatusEnum.OPERATION_TIME_INVALID.getCode(),
                SchedulingOperationStatusEnum.PRECEDENCE_INVALID.getCode(),
                SchedulingOperationStatusEnum.ROUTE_GRAPH_INVALID.getCode(),
                SchedulingOperationStatusEnum.COMPLETION_AFTER_DEMAND.getCode()).contains(status);
    }

    private boolean overlaps(PlnSchedulingOperationEntity left, PlnSchedulingOperationEntity right) {
        return left.getScheduledStartTime() != null && left.getScheduledEndTime() != null
                && right.getScheduledStartTime() != null && right.getScheduledEndTime() != null
                && left.getScheduledStartTime().isBefore(right.getScheduledEndTime())
                && left.getScheduledEndTime().isAfter(right.getScheduledStartTime());
    }

    private boolean overlaps(LocalDateTime leftStart, LocalDateTime leftEnd,
                             LocalDateTime rightStart, LocalDateTime rightEnd) {
        return leftStart != null && leftEnd != null && rightStart != null && rightEnd != null
                && leftStart.isBefore(rightEnd) && leftEnd.isAfter(rightStart);
    }

    private boolean overlaps(PlnSchedulingOperationEntity operation, TpmDeviceUnavailableWindowDTO window) {
        return operation.getScheduledStartTime() != null && operation.getScheduledEndTime() != null
                && window.getStartTime() != null && operation.getScheduledEndTime().isAfter(window.getStartTime())
                && (window.getEndTime() == null || operation.getScheduledStartTime().isBefore(window.getEndTime()));
    }

    private boolean sharesResource(PlnSchedulingOperationEntity left, PlnSchedulingOperationEntity right) {
        Set<String> rightKeys = new HashSet<>(operationKeys(right));
        return operationKeys(left).stream().anyMatch(rightKeys::contains);
    }

    private Set<String> csvSet(String value) {
        if (StrUtil.isBlank(value)) {
            return Set.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> csvList(String value) {
        return new ArrayList<>(csvSet(value));
    }

    /**
     * 在同一次可用窗口搜索中计算相邻产品族。窗口因设备、人员或工装冲突向后移动时，
     * 前序工单可能改变，因此换型规则和占用时长也必须同步重算，不能先算一次再平移。
     */
    private TransitionAllocation allocateWithChangeover(List<String> resourceKeys, ResourceCandidate candidate,
                                                        ToolingChoice toolingChoice, LocalDateTime earliest, DurationParts baseDuration,
                                                        PlnIssueEntity issue, String currentFamily, String processCode,
                                                        Map<String, List<BookedInterval>> bookings, WorkingCalendar calendar,
                                                        ChangeoverCatalog catalog) {
        LocalDateTime cursor = calendar.nextWorkingInstant(earliest);
        List<BookedInterval> resourceBookings = resourceKeys.stream()
                .flatMap(key -> bookings.getOrDefault(key, List.of()).stream()).toList();
        List<ScopeResource> scopes = new ArrayList<>();
        if ("STATION".equals(candidate.type)) {
            scopes.add(new ScopeResource("STATION", candidate.code, candidate.key()));
        }
        if (StrUtil.isNotBlank(candidate.deviceCode)) {
            scopes.add(new ScopeResource("DEVICE", candidate.deviceCode, deviceKey(candidate.deviceCode)));
        }
        toolingChoice.selected.stream().map(MdmToolingCandidateDTO::getToolingCode)
                .filter(StrUtil::isNotBlank).distinct()
                .map(code -> new ScopeResource("TOOLING", code, toolingKey(code))).forEach(scopes::add);
        for (int guard = 0; guard < 10000 && cursor != null; guard++) {
            com.kitex.mom.plan.service.SchedulingExecutionContext.check();
            TransitionResolution resolution = resolveChangeover(scopes, cursor, processCode, currentFamily,
                    bookings, catalog);
            if (resolution.block != null) return TransitionAllocation.blocked(resolution.block);
            long totalMinutes = baseDuration.activeMinutes + resolution.transition.minutes;
            LocalDateTime end = calendar.addWorkingMinutes(cursor, totalMinutes);
            if (end == null) return TransitionAllocation.unavailable();
            BookedInterval conflict = null;
            for (BookedInterval item : resourceBookings) {
                if (cursor.isBefore(item.end) && end.isAfter(item.start)
                        && (conflict == null || item.end.isBefore(conflict.end))) {
                    conflict = item;
                }
            }
            if (conflict == null) {
                return TransitionAllocation.scheduled(new Allocation(cursor, end), resolution.transition);
            }
            cursor = calendar.nextWorkingInstant(conflict.end);
        }
        return TransitionAllocation.unavailable();
    }

    private TransitionResolution resolveChangeover(List<ScopeResource> scopes, LocalDateTime at,
                                                   String processCode, String currentFamily, Map<String, List<BookedInterval>> bookings,
                                                   ChangeoverCatalog catalog) {
        if (scopes.isEmpty() || StrUtil.isBlank(currentFamily) || catalog.rules.isEmpty()) {
            return TransitionResolution.ok(ChangeoverTransition.none());
        }
        long minutes = 0L;
        List<MdmChangeoverRuleEntity> selected = new ArrayList<>();
        Set<String> precedingProducts = new LinkedHashSet<>();
        Set<String> precedingFamilies = new LinkedHashSet<>();
        for (ScopeResource scope : scopes) {
            List<MdmChangeoverRuleEntity> governed = catalog.rules.stream()
                    .filter(rule -> Objects.equals(scope.scopeType, rule.getScopeType()))
                    .filter(rule -> Objects.equals(scope.resourceCode, rule.getResourceCode()))
                    .filter(rule -> StrUtil.isBlank(rule.getProcessCode())
                            || Objects.equals(processCode, rule.getProcessCode()))
                    .filter(rule -> !rule.getValidStartTime().isAfter(at)
                            && (rule.getValidEndTime() == null || rule.getValidEndTime().isAfter(at)))
                    .toList();
            if (governed.isEmpty()) continue;
            BookedInterval previous = bookings.getOrDefault(scope.bookingKey, List.of()).stream()
                    .filter(item -> "APS_OPERATION".equals(item.sourceType))
                    .filter(item -> StrUtil.isNotBlank(item.schedulingFamilyCode))
                    .filter(item -> !item.end.isAfter(at))
                    .max(Comparator.comparing(BookedInterval::end)).orElse(null);
            if (previous == null) continue;
            if (StrUtil.isNotBlank(previous.productCode)) precedingProducts.add(previous.productCode);
            precedingFamilies.add(previous.schedulingFamilyCode);
            List<MdmChangeoverRuleEntity> matched = governed.stream()
                    .filter(rule -> Objects.equals(previous.schedulingFamilyCode, rule.getFromFamilyCode()))
                    .filter(rule -> Objects.equals(currentFamily, rule.getToFamilyCode()))
                    .sorted(Comparator
                            .comparing((MdmChangeoverRuleEntity rule) -> StrUtil.isBlank(rule.getProcessCode()) ? 1 : 0)
                            .thenComparing(rule -> Optional.ofNullable(rule.getPriority()).orElse(100))
                            .thenComparing(MdmChangeoverRuleEntity::getVersionNo,
                                    Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            if (matched.isEmpty()) {
                if (Objects.equals(previous.schedulingFamilyCode, currentFamily)) continue;
                return TransitionResolution.blocked(new TransitionBlock(
                        SchedulingOperationStatusEnum.CHANGEOVER_RULE_MISSING.getCode(),
                        "在" + scope.scopeType + "[" + scope.resourceCode + "]上，产品族["
                                + previous.schedulingFamilyCode + "]→[" + currentFamily + "]缺少生效换型规则",
                        scope.scopeType, scope.resourceCode, previous.schedulingFamilyCode, currentFamily));
            }
            MdmChangeoverRuleEntity rule = matched.get(0);
            if (Boolean.TRUE.equals(rule.getForbidden())) {
                return TransitionResolution.blocked(new TransitionBlock(
                        SchedulingOperationStatusEnum.CHANGEOVER_FORBIDDEN.getCode(),
                        "命中禁排换型规则[" + rule.getRuleNo() + "]：" + scope.scopeType + "["
                                + scope.resourceCode + "]产品族[" + previous.schedulingFamilyCode + "]→["
                                + currentFamily + "]禁止相邻排产",
                        scope.scopeType, scope.resourceCode, previous.schedulingFamilyCode, currentFamily));
            }
            selected.add(rule);
            minutes = Math.max(minutes, Optional.ofNullable(rule.getTotalMinutes()).orElse(0L));
        }
        return TransitionResolution.ok(new ChangeoverTransition(minutes, selected,
                precedingProducts, precedingFamilies));
    }

    private Allocation allocate(List<String> resourceKeys, LocalDateTime earliest, long minutes,
                                Map<String, List<BookedInterval>> bookings, WorkingCalendar calendar) {
        LocalDateTime cursor = calendar.nextWorkingInstant(earliest);
        List<BookedInterval> resourceBookings = resourceKeys.stream()
                .flatMap(key -> bookings.getOrDefault(key, List.of()).stream()).toList();
        for (int guard = 0; guard < 10000 && cursor != null; guard++) {
            LocalDateTime end = calendar.addWorkingMinutes(cursor, minutes);
            com.kitex.mom.plan.service.SchedulingExecutionContext.check();
            if (end == null) {
                return null;
            }
            BookedInterval conflict = null;
            for (BookedInterval item : resourceBookings) {
                if (cursor.isBefore(item.end) && end.isAfter(item.start)
                        && (conflict == null || item.end.isBefore(conflict.end))) {
                    conflict = item;
                }
            }
            if (conflict == null) {
                return new Allocation(cursor, end);
            }
            cursor = calendar.nextWorkingInstant(conflict.end);
        }
        return null;
    }

    private DurationParts durationOf(BigDecimal quantity, MdmProcedureEntity procedure,
                                     ResourceCandidate candidate, long dailyMinutes,
                                     DurationCalibrationCatalog calibration) {
        BigDecimal effectiveQuantity = quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ONE : quantity;
        DurationCalibrationResolution calibrated = calibration == null
                ? DurationCalibrationResolution.none() : calibration.resolve(procedure.getProcessCode(), candidate);
        if (calibrated.profile != null) {
            MdmDurationCalibrationProposalEntity profile = calibrated.profile;
            long fixedSeconds = Math.max(0L, Optional.ofNullable(profile.getProposedFixedSeconds()).orElse(0L));
            BigDecimal unitSeconds = Optional.ofNullable(profile.getProposedUnitSeconds()).orElse(BigDecimal.ZERO);
            if (unitSeconds.signum() > 0) {
                long originalSetup = toSeconds(procedure.getSetupTime(), procedure.getSetupTimeUnit());
                long originalDisassembly = toSeconds(procedure.getDisassemblyTime(), procedure.getDisassemblyTimeUnit());
                long originalFixed = originalSetup + originalDisassembly;
                long fixedMinutes = secondsToMinutes(fixedSeconds);
                long setup = originalFixed <= 0 ? fixedMinutes : BigDecimal.valueOf(fixedMinutes)
                        .multiply(BigDecimal.valueOf(originalSetup))
                        .divide(BigDecimal.valueOf(originalFixed), 0, RoundingMode.HALF_UP).longValue();
                long disassembly = Math.max(0L, fixedMinutes - setup);
                long run = secondsToMinutes(unitSeconds.multiply(effectiveQuantity)
                        .setScale(0, RoundingMode.CEILING).longValue());
                return new DurationParts(setup, run, disassembly, setup + run + disassembly);
            }
        }
        long setup = toMinutes(procedure.getSetupTime(), procedure.getSetupTimeUnit());
        long disassembly = toMinutes(procedure.getDisassemblyTime(), procedure.getDisassemblyTimeUnit());
        long run;
        if (candidate.stationCapacity != null && candidate.stationCapacity.compareTo(BigDecimal.ZERO) > 0) {
            run = effectiveQuantity.multiply(BigDecimal.valueOf(60))
                    .divide(candidate.stationCapacity, 0, RoundingMode.CEILING).longValue();
        } else {
            BigDecimal batch = procedure.getActivityBasicBatch() == null
                    || procedure.getActivityBasicBatch().compareTo(BigDecimal.ZERO) <= 0
                    ? BigDecimal.ONE : procedure.getActivityBasicBatch();
            long batches = effectiveQuantity.divide(batch, 0, RoundingMode.CEILING).max(BigDecimal.ONE).longValue();
            if (candidate.standardWorkingTime != null && candidate.standardWorkingTime.compareTo(BigDecimal.ZERO) > 0) {
                run = candidate.standardWorkingTime.multiply(BigDecimal.valueOf(batches))
                        .setScale(0, RoundingMode.CEILING).longValue();
            } else {
                long manual = toMinutes(procedure.getManualProcessingTime(), procedure.getManualProcessingTimeUnit());
                long machine = toMinutes(procedure.getMachineProcessingTime(), procedure.getMachineProcessingTimeUnit());
                run = Math.max(manual, machine) * batches;
            }
        }
        if ("OUTSOURCE".equals(candidate.type) && procedure.getOutsourcingLeadTimeDays() != null
                && procedure.getOutsourcingLeadTimeDays() > 0) {
            run = Math.max(run, procedure.getOutsourcingLeadTimeDays() * dailyMinutes);
        }
        if (run <= 0 && procedure.getLeadTimeHours() != null
                && procedure.getLeadTimeHours().compareTo(BigDecimal.ZERO) > 0) {
            run = toMinutes(procedure.getLeadTimeHours(), "HOUR");
        }
        return new DurationParts(setup, run, disassembly, setup + run + disassembly);
    }

    private long toSeconds(BigDecimal value, String unit) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) return 0L;
        String normalized = StrUtil.blankToDefault(unit, "MINUTE").toUpperCase(Locale.ROOT);
        BigDecimal factor = normalized.contains("HOUR") || normalized.equals("H") || normalized.contains("小时")
                ? BigDecimal.valueOf(3600) : normalized.contains("DAY") || normalized.equals("D") || normalized.contains("天")
                ? BigDecimal.valueOf(28800) : normalized.contains("SECOND") || normalized.equals("S") || normalized.contains("秒")
                ? BigDecimal.ONE : BigDecimal.valueOf(60);
        return value.multiply(factor).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private long secondsToMinutes(long seconds) {
        return seconds <= 0 ? 0L : BigDecimal.valueOf(seconds)
                .divide(BigDecimal.valueOf(60), 0, RoundingMode.CEILING).longValue();
    }

    private LocalDateTime calculateTransferReadyTime(PlnSchedulingOperationEntity operation,
                                                     DurationParts duration, BigDecimal plannedQuantity,
                                                     WorkingCalendar calendar) {
        long releasableMinutes = duration.activeMinutes();
        BigDecimal transferBatch = operation.getTransferBatchQuantity();
        if (Boolean.TRUE.equals(operation.getOverlapEnabled()) && transferBatch != null
                && transferBatch.signum() > 0 && plannedQuantity != null && plannedQuantity.signum() > 0) {
            BigDecimal effectiveBatch = transferBatch.min(plannedQuantity);
            long batchRunMinutes = BigDecimal.valueOf(duration.runMinutes()).multiply(effectiveBatch)
                    .divide(plannedQuantity, 0, RoundingMode.CEILING).longValue();
            releasableMinutes = duration.setupMinutes() + batchRunMinutes;
        }
        LocalDateTime release = calendar.addWorkingMinutes(operation.getScheduledStartTime(), releasableMinutes);
        if (release == null || release.isAfter(operation.getScheduledEndTime())) {
            release = operation.getScheduledEndTime();
        }
        return release.plusMinutes(Optional.ofNullable(operation.getTransferMinutes()).orElse(0L));
    }

    private List<ResourceCandidate> resourceCandidates(MdmProcessRouteDTO route,
                                                       MdmProcessRouteWorkingVO working,
                                                       MdmProcedureEntity procedure) {
        if (isOutsource(procedure)) {
            return List.of(new ResourceCandidate("OUTSOURCE", "OUTSOURCE:" + working.getProcessCode(),
                    "委外-" + working.getProcessName(), 0, null, null, true, null, null, null, null, null));
        }
        List<MdmProcessProcedureWorkVO> works = Optional.ofNullable(working.getMdmProcessProcedureWorkList()).orElse(List.of());
        Map<String, ResourceCandidate> stationMap = new LinkedHashMap<>();
        for (MdmProcessProcedureWorkVO item : works) {
            if (StrUtil.isBlank(item.getWorkCode())) {
                continue;
            }
            int priority = (Boolean.TRUE.equals(item.getPrimary()) ? 0 : 10000)
                    + Optional.ofNullable(item.getPriority()).orElse(1000);
            String stationName = StrUtil.blankToDefault(item.getWorkName(), item.getWorkCode());
            String[] deviceCodes = item.getDeviceCodes();
            String[] deviceNames = item.getDeviceNames();
            String[] employeeCodes = item.getEmployeeCodes();
            String[] employeeNames = item.getEmployeeNames();
            List<Integer> deviceIndexes = deviceCodes == null ? new ArrayList<>()
                    : java.util.stream.IntStream.range(0, deviceCodes.length)
                    .filter(i -> StrUtil.isNotBlank(deviceCodes[i])).boxed().toList();
            List<Integer> employeeIndexes = employeeCodes == null ? new ArrayList<>()
                    : java.util.stream.IntStream.range(0, employeeCodes.length)
                    .filter(i -> StrUtil.isNotBlank(employeeCodes[i])).boxed().toList();
            if (deviceIndexes.isEmpty()) {
                deviceIndexes = Collections.singletonList(-1);
            }
            if (employeeIndexes.isEmpty()) {
                employeeIndexes = Collections.singletonList(-1);
            }
            int parallelCapacity = Math.max(1, Optional.ofNullable(item.getParallelCapacity()).orElse(1));
            boolean grouped = StrUtil.isNotBlank(item.getResourceGroupCode());
            int resourceGroupCapacity = grouped
                    ? Math.max(1, Optional.ofNullable(item.getResourceGroupCapacity()).orElse(1)) : 1;
            for (int slotNo = 1; slotNo <= parallelCapacity; slotNo++) {
                for (int groupSlotNo = 1; groupSlotNo <= resourceGroupCapacity; groupSlotNo++) {
                    for (Integer deviceIndex : deviceIndexes) {
                        for (Integer employeeIndex : employeeIndexes) {
                            String deviceCode = deviceIndex < 0 ? null : deviceCodes[deviceIndex];
                            String deviceName = deviceIndex < 0 ? null
                                    : deviceNames != null && deviceIndex < deviceNames.length
                                    ? StrUtil.blankToDefault(deviceNames[deviceIndex], deviceCode) : deviceCode;
                            String employeeCode = employeeIndex < 0 ? null : employeeCodes[employeeIndex];
                            String employeeName = employeeIndex < 0 ? null
                                    : employeeNames != null && employeeIndex < employeeNames.length
                                    ? StrUtil.blankToDefault(employeeNames[employeeIndex], employeeCode) : employeeCode;
                            ResourceCandidate candidate = new ResourceCandidate("STATION", item.getWorkCode(), stationName,
                                    priority, item.getStationCapacity(), item.getStandardWorkingTime(), false,
                                    deviceCode, deviceName, employeeCode, employeeName, slotNo,
                                    grouped ? item.getResourceGroupCode() : null,
                                    grouped ? StrUtil.blankToDefault(item.getResourceGroupName(), item.getResourceGroupCode()) : null,
                                    grouped ? resourceGroupCapacity : null, grouped ? groupSlotNo : null);
                            stationMap.merge(candidate.candidateKey(), candidate,
                                    (left, right) -> left.priority <= right.priority ? left : right);
                        }
                    }
                }
            }
        }
        List<ResourceCandidate> stations = stationMap.values().stream()
                .sorted(Comparator.comparingInt(ResourceCandidate::priority)
                        .thenComparing(item -> StrUtil.blankToDefault(item.deviceCode, ""))
                        .thenComparing(item -> StrUtil.blankToDefault(item.employeeCode, ""))).toList();
        if (!stations.isEmpty()) {
            return stations;
        }
        List<ResourceCandidate> lines = works.stream().filter(item -> StrUtil.isNotBlank(item.getLineCode()))
                .map(item -> new ResourceCandidate("LINE", item.getLineCode(),
                        StrUtil.blankToDefault(item.getLineName(), item.getLineCode()), 1000,
                        null, null, true, null, null, null, null, null))
                .collect(Collectors.toMap(ResourceCandidate::candidateKey, item -> item, (a, b) -> a, LinkedHashMap::new))
                .values().stream().toList();
        if (!lines.isEmpty()) {
            return lines;
        }
        if (StrUtil.isNotBlank(route.getProductLineCode())) {
            return List.of(new ResourceCandidate("LINE", route.getProductLineCode(),
                    StrUtil.blankToDefault(route.getProductLineName(), route.getProductLineCode()),
                    1000, null, null, true, null, null, null, null, null));
        }
        String workshopCode = StrUtil.blankToDefault(working.getWorkshopCode(), route.getWorkshopCode());
        String workshopName = StrUtil.blankToDefault(working.getWorkshopName(), route.getWorkshopName());
        if (StrUtil.isNotBlank(workshopCode)) {
            return List.of(new ResourceCandidate("WORKSHOP", workshopCode,
                    StrUtil.blankToDefault(workshopName, workshopCode), 1000, null, null, true,
                    null, null, null, null, null));
        }
        return List.of();
    }

    /**
     * 将工位上可执行的单个员工扩展成满足各技能人数要求的并发执行团队。一个员工可以同时覆盖多项技能，
     * 但同一项技能的requiredCount必须由不同员工覆盖；同班组组合优先，跨班组组合仍可作为可执行备选。
     */
    private List<ResourceCandidate> expandWorkforceCandidates(List<ResourceCandidate> baseCandidates,
                                                              WorkforceAvailabilityCatalog workforce, String processCode) {
        List<MdmProcedureSkillRequirementEntity> requirements = workforce.requirements(processCode);
        if (requirements.isEmpty()) {
            return baseCandidates;
        }
        Map<String, List<ResourceCandidate>> byResource = baseCandidates.stream()
                .filter(ResourceCandidate::employeeBound)
                .collect(Collectors.groupingBy(ResourceCandidate::baseCandidateKey, LinkedHashMap::new,
                        Collectors.toList()));
        List<ResourceCandidate> result = new ArrayList<>();
        for (List<ResourceCandidate> resourceMembers : byResource.values()) {
            int resourceLimit = result.size() + MAX_WORKFORCE_COMBINATIONS;
            List<ResourceCandidate> members = resourceMembers.stream()
                    .collect(Collectors.toMap(item -> item.employeeCode, item -> item,
                            (a, b) -> a.priority <= b.priority ? a : b, LinkedHashMap::new))
                    .values().stream()
                    .filter(item -> {
                        MdmWorkforceCandidateDTO candidate = workforce.get(item.employeeCode, processCode);
                        return candidate != null && Boolean.TRUE.equals(candidate.getEmployeeActive())
                                && CollUtil.isNotEmpty(candidate.getMatchedSkillCodes());
                    })
                    .sorted(Comparator.comparing(item -> item.employeeCode)).toList();
            if (members.isEmpty()) {
                continue;
            }
            int lowerSize = requirements.stream().map(MdmProcedureSkillRequirementEntity::getRequiredCount)
                    .filter(Objects::nonNull).max(Integer::compareTo).orElse(1);
            int upperSize = Math.min(members.size(), requirements.stream()
                    .mapToInt(item -> Optional.ofNullable(item.getRequiredCount()).orElse(1)).sum());
            for (int size = lowerSize; size <= upperSize && result.size() < resourceLimit; size++) {
                collectWorkforceCombinations(members, 0, size, new ArrayList<>(), result,
                        resourceLimit, workforce, processCode, requirements);
            }
        }
        return result.stream().sorted(Comparator.comparingInt(ResourceCandidate::priority)
                .thenComparing(ResourceCandidate::employeeCode)).toList();
    }

    private void collectWorkforceCombinations(List<ResourceCandidate> members, int start, int targetSize,
                                              List<ResourceCandidate> selected, List<ResourceCandidate> result,
                                              int resultLimit,
                                              WorkforceAvailabilityCatalog workforce, String processCode,
                                              List<MdmProcedureSkillRequirementEntity> requirements) {
        if (result.size() >= resultLimit) {
            return;
        }
        if (selected.size() == targetSize) {
            List<MdmWorkforceCandidateDTO> workers = selected.stream()
                    .map(item -> workforce.get(item.employeeCode, processCode)).filter(Objects::nonNull).toList();
            if (!workforceGroupQualified(workers, requirements, null, null)) {
                return;
            }
            ResourceCandidate first = selected.get(0);
            String employeeCodes = selected.stream().map(ResourceCandidate::employeeCode)
                    .distinct().sorted().collect(Collectors.joining(","));
            Map<String, String> names = selected.stream().collect(Collectors.toMap(
                    ResourceCandidate::employeeCode, item -> StrUtil.blankToDefault(item.employeeName, item.employeeCode),
                    (a, b) -> a, TreeMap::new));
            Set<String> commonTeams = commonTeamCodes(workers);
            int teamPenalty = selected.size() > 1 && commonTeams.isEmpty() ? 500 : 0;
            result.add(new ResourceCandidate(first.type, first.code, first.name,
                    first.priority + selected.size() * 10 + teamPenalty, first.stationCapacity,
                    first.standardWorkingTime, first.fallback, first.deviceCode, first.deviceName,
                    employeeCodes, String.join(",", names.values()), first.slotNo,
                    first.resourceGroupCode, first.resourceGroupName, first.resourceGroupCapacity,
                    first.resourceGroupSlotNo));
            return;
        }
        int remaining = targetSize - selected.size();
        for (int index = start; index <= members.size() - remaining; index++) {
            selected.add(members.get(index));
            collectWorkforceCombinations(members, index + 1, targetSize, selected, result,
                    resultLimit, workforce, processCode, requirements);
            selected.remove(selected.size() - 1);
            if (result.size() >= resultLimit) {
                return;
            }
        }
    }

    private Set<String> commonTeamCodes(List<MdmWorkforceCandidateDTO> workers) {
        Set<String> common = null;
        for (MdmWorkforceCandidateDTO worker : workers) {
            Set<String> current = new LinkedHashSet<>(Optional.ofNullable(worker.getTeamCodes()).orElse(List.of()));
            if (common == null) {
                common = current;
            } else {
                common.retainAll(current);
            }
        }
        return common == null ? Set.of() : common;
    }

    private boolean workforceGroupQualified(List<MdmWorkforceCandidateDTO> workers,
                                            List<MdmProcedureSkillRequirementEntity> requirements,
                                            LocalDateTime start, LocalDateTime end) {
        return workforceGroupGap(workers, requirements, start, end) == null;
    }

    private String workforceGroupGap(List<MdmWorkforceCandidateDTO> workers,
                                     List<MdmProcedureSkillRequirementEntity> requirements,
                                     LocalDateTime start, LocalDateTime end) {
        if (workers.isEmpty()) {
            return "没有可核验的候选员工";
        }
        if (workers.stream().anyMatch(item -> !Boolean.TRUE.equals(item.getEmployeeActive()))) {
            return "包含不存在或已离职员工";
        }
        for (MdmProcedureSkillRequirementEntity requirement : requirements) {
            int required = Optional.ofNullable(requirement.getRequiredCount()).orElse(1);
            long actual = workers.stream().filter(worker -> workforceSkillMatched(worker, requirement, start, end))
                    .map(MdmWorkforceCandidateDTO::getEmployeeCode).distinct().count();
            if (actual < required) {
                return "技能[" + requirement.getSkillCode() + "/" + requirement.getSkillName() + "]要求"
                        + required + "人，当前仅" + actual + "人满足等级、证书及有效期";
            }
        }
        return null;
    }

    private boolean workforceSkillMatched(MdmWorkforceCandidateDTO worker,
                                          MdmProcedureSkillRequirementEntity requirement, LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return Optional.ofNullable(worker.getMatchedSkillCodes()).orElse(List.of())
                    .contains(requirement.getSkillCode());
        }
        LocalDate startDate = start.toLocalDate();
        LocalDate endDate = end.minusNanos(1).toLocalDate();
        return Optional.ofNullable(worker.getSkillSnapshot()).orElse(List.of()).stream().anyMatch(skill ->
                Objects.equals(requirement.getSkillCode(), skill.getSkillCode())
                        && Optional.ofNullable(skill.getProficiencyLevel()).orElse(0)
                        >= Optional.ofNullable(requirement.getMinimumLevel()).orElse(1)
                        && (!Boolean.TRUE.equals(requirement.getCertificateRequired())
                        || StrUtil.isNotBlank(skill.getCertificateNo()))
                        && (skill.getValidFrom() == null || !startDate.isBefore(skill.getValidFrom()))
                        && (skill.getValidTo() == null || !endDate.isAfter(skill.getValidTo())));
    }

    private WorkingCalendar workforceCalendar(WorkingCalendar base,
                                              List<MdmWorkforceCandidateDTO> workers,
                                              List<MdmProcedureSkillRequirementEntity> requirements) {
        WorkingCalendar common = base;
        for (MdmWorkforceCandidateDTO worker : workers) {
            common = common.intersect(Optional.ofNullable(worker.getAvailableWindows()).orElse(List.of()));
        }
        if (requirements.isEmpty()) {
            return common;
        }
        List<TimeWindow> qualified = new ArrayList<>();
        for (TimeWindow window : common.windows) {
            LocalDateTime cursor = window.start;
            while (cursor.isBefore(window.end)) {
                LocalDateTime nextDay = cursor.toLocalDate().plusDays(1).atStartOfDay();
                LocalDateTime segmentEnd = nextDay.isBefore(window.end) ? nextDay : window.end;
                if (workforceGroupQualified(workers, requirements, cursor, segmentEnd)) {
                    qualified.add(new TimeWindow(cursor, segmentEnd));
                }
                cursor = segmentEnd;
            }
        }
        return new WorkingCalendar(qualified, common.dailyMinutes);
    }

    private List<TimeWindow> workforceWindowUnion(List<MdmWorkforceCandidateDTO> workers) {
        return workers.stream().flatMap(worker -> Optional.ofNullable(worker.getAvailableWindows())
                        .orElse(List.of()).stream())
                .filter(item -> item.getStartTime() != null && item.getEndTime() != null)
                .map(item -> new TimeWindow(item.getStartTime(), item.getEndTime())).toList();
    }

    private boolean resourceBindingValid(PlnSchedulingOperationEntity operation,
                                         List<ResourceCandidate> currentCandidates) {
        Integer operationSlotNo = operation.getResourceSlotNo() == null
                && "STATION".equalsIgnoreCase(operation.getResourceType()) ? 1 : operation.getResourceSlotNo();
        List<ResourceCandidate> sameResource = currentCandidates.stream()
                .filter(item -> Objects.equals(item.type, operation.getResourceType()))
                .filter(item -> Objects.equals(item.code, operation.getResourceCode()))
                .filter(item -> Objects.equals(StrUtil.blankToDefault(item.deviceCode, ""),
                        StrUtil.blankToDefault(operation.getDeviceCode(), "")))
                .filter(item -> Objects.equals(item.slotNo, operationSlotNo)).toList();
        if (StrUtil.isNotBlank(operation.getResourceGroupCode())) {
            sameResource = sameResource.stream()
                    .filter(item -> Objects.equals(item.resourceGroupCode, operation.getResourceGroupCode()))
                    .filter(item -> Objects.equals(item.resourceGroupSlotNo, operation.getResourceGroupSlotNo()))
                    .filter(item -> Objects.equals(item.resourceGroupCapacity, operation.getResourceGroupCapacity()))
                    .toList();
        } else {
            sameResource = sameResource.stream().filter(item -> !item.groupBound()).toList();
        }
        if (sameResource.isEmpty()) {
            return false;
        }
        Set<String> selectedWorkers = csvSet(operation.getEmployeeCode());
        if (selectedWorkers.isEmpty()) {
            return sameResource.stream().anyMatch(item -> !item.employeeBound());
        }
        Set<String> currentlyBound = sameResource.stream().flatMap(item -> item.employeeCodes().stream())
                .collect(Collectors.toSet());
        return currentlyBound.containsAll(selectedWorkers);
    }

    private ResourceCandidate selectedResourceCandidate(PlnSchedulingOperationEntity operation,
                                                        List<ResourceCandidate> currentCandidates) {
        Integer slotNo = operation.getResourceSlotNo() == null
                && "STATION".equalsIgnoreCase(operation.getResourceType()) ? 1 : operation.getResourceSlotNo();
        return currentCandidates.stream()
                .filter(item -> Objects.equals(item.type, operation.getResourceType()))
                .filter(item -> Objects.equals(item.code, operation.getResourceCode()))
                .filter(item -> Objects.equals(StrUtil.blankToDefault(item.deviceCode, ""),
                        StrUtil.blankToDefault(operation.getDeviceCode(), "")))
                .filter(item -> Objects.equals(item.slotNo, slotNo))
                .filter(item -> Objects.equals(StrUtil.blankToDefault(item.resourceGroupCode, ""),
                        StrUtil.blankToDefault(operation.getResourceGroupCode(), "")))
                .filter(item -> Objects.equals(item.resourceGroupSlotNo, operation.getResourceGroupSlotNo()))
                .findFirst().orElse(null);
    }

    private boolean isOutsource(MdmProcedureEntity procedure) {
        String type = StrUtil.blankToDefault(procedure.getProcessingType(), "").toUpperCase(Locale.ROOT);
        return type.contains("OUT") || type.contains("委外") || type.contains("外协");
    }

    private OperationMaterialCatalog operationMaterialCatalog(PlnIssueEntity issue) {
        return operationMaterialCatalog(issue == null ? null : issue.getOperationMaterialConstraintSnapshot());
    }

    private void applyBatchMaterialContext(PlnIssueEntity target,
                                           List<PlnSchedulingTaskDetailEntity> sourceDetails) {
        if (sourceDetails.isEmpty()) {
            target.setMaterialHardBlocked(Boolean.TRUE);
            target.setMaterialConstraintSummary("排产批次缺少来源计划的逐层物料证据");
            return;
        }
        if (sourceDetails.size() == 1) {
            PlnSchedulingTaskDetailEntity source = sourceDetails.get(0);
            target.setMaterialConstraintStatus(source.getMaterialConstraintStatus());
            target.setMaterialAvailableDate(source.getMaterialAvailableDate());
            target.setMaterialAvailableTime(source.getMaterialAvailableTime());
            target.setMaterialHardBlocked(source.getMaterialHardBlocked());
            target.setMaterialConstraintSummary(source.getMaterialConstraintSummary());
            target.setMaterialConstraintSnapshot(source.getMaterialConstraintSnapshot());
            target.setOperationMaterialConstraintSnapshot(source.getOperationMaterialConstraintSnapshot());
            target.setMaterialLineageFingerprint(source.getMaterialLineageFingerprint());
            target.setMaterialConstraintCheckedTime(source.getMaterialConstraintCheckedTime());
            return;
        }
        if (sourceDetails.stream().anyMatch(item -> StrUtil.isBlank(
                item.getOperationMaterialConstraintSnapshot())
                || StrUtil.isBlank(item.getMaterialLineageFingerprint()))) {
            target.setMaterialHardBlocked(Boolean.TRUE);
            target.setMaterialConstraintStatus("BOM_LINEAGE_INVALID");
            target.setMaterialConstraintSummary("合批来源中存在未生成逐工序约束或BOM路径指纹的计划");
            return;
        }
        Map<String, List<Map.Entry<Long, PlnOperationMaterialConstraintDTO>>> grouped = new LinkedHashMap<>();
        for (PlnSchedulingTaskDetailEntity detail : sourceDetails) {
            OperationMaterialCatalog catalog = operationMaterialCatalog(
                    detail.getOperationMaterialConstraintSnapshot());
            catalog.byOperation.forEach((operationKey, value) -> grouped
                    .computeIfAbsent(operationKey, ignored -> new ArrayList<>())
                    .add(Map.entry(detail.getIssueId(), value)));
        }
        List<PlnOperationMaterialConstraintDTO> merged = new ArrayList<>();
        for (Map.Entry<String, List<Map.Entry<Long, PlnOperationMaterialConstraintDTO>>> entry : grouped.entrySet()) {
            List<Map.Entry<Long, PlnOperationMaterialConstraintDTO>> values = entry.getValue();
            Set<String> routeCodes = values.stream().map(item -> item.getValue().getRouteCode())
                    .filter(StrUtil::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new));
            if (routeCodes.size() > 1) throw new CheckedException("合批来源使用了不同工艺路线，不能合并物料约束");
            LocalDateTime availableTime = values.stream().map(item -> constraintTime(item.getValue()))
                    .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
            LocalDate available = availableTime == null ? null : availableTime.toLocalDate();
            String timePrecision = mergedTimePrecision(values.stream().map(Map.Entry::getValue)
                    .filter(item -> Objects.equals(constraintTime(item), availableTime)).toList());
            boolean hardBlocked = values.stream().anyMatch(item -> Boolean.TRUE.equals(
                    item.getValue().getHardBlocked()));
            List<String> materialCodes = values.stream()
                    .flatMap(item -> Optional.ofNullable(item.getValue().getMaterialCodes())
                            .orElse(List.of()).stream()).distinct().sorted().toList();
            List<String> bomPaths = values.stream()
                    .flatMap(item -> Optional.ofNullable(item.getValue().getBomPaths())
                            .orElse(List.of()).stream()).distinct().sorted().toList();
            String evidence = values.stream().sorted(Map.Entry.comparingByKey())
                    .map(item -> item.getKey() + ":" + item.getValue().getEvidenceFingerprint())
                    .collect(Collectors.joining("|"));
            Set<String> processes = values.stream().map(item -> item.getValue().getProcessCode())
                    .collect(Collectors.toSet());
            if (processes.size() != 1) throw new CheckedException("合批投料节点对应的主工序不一致");
            PlnOperationMaterialConstraintDTO identity = values.get(0).getValue();
            merged.add(new PlnOperationMaterialConstraintDTO()
                    .setRouteCode(routeCodes.stream().findFirst().orElse(null))
                    .setProcessCode(identity.getProcessCode()).setSourceRouteNodeCode(identity.getSourceRouteNodeCode())
                    .setMaterialAvailableDate(available).setMaterialAvailableTime(availableTime)
                    .setMaterialTimePrecision(timePrecision)
                    .setHardBlocked(hardBlocked)
                    .setStatus(hardBlocked ? "BLOCKED" : values.stream().anyMatch(item ->
                            "WAITING_SUPPLY".equals(item.getValue().getStatus())) ? "WAITING_SUPPLY" : "READY")
                    .setSummary("合批" + sourceDetails.size() + "个来源计划，工序" + identity.getProcessCode()
                            + (identity.getSourceRouteNodeCode() == null ? "" : "[" + identity.getSourceRouteNodeCode() + "]")
                            + "的" + materialCodes.size() + "项物料于" + available + "汇合")
                    .setMaterialCodes(materialCodes).setBomPaths(bomPaths)
                    .setEvidenceFingerprint(stableHash(evidence)));
        }
        merged.sort(Comparator.comparing(PlnRouteNodeSchedulingSupport::materialKey));
        List<String> lineage = sourceDetails.stream()
                .map(item -> item.getIssueId() + ":" + item.getMaterialLineageFingerprint()).sorted().toList();
        target.setOperationMaterialConstraintSnapshot(toJson(merged));
        target.setMaterialLineageFingerprint(stableHash(String.join("|", lineage)));
        target.setMaterialAvailableDate(sourceDetails.stream().map(
                        PlnSchedulingTaskDetailEntity::getMaterialAvailableDate).filter(Objects::nonNull)
                .max(LocalDate::compareTo).orElse(null));
        target.setMaterialAvailableTime(sourceDetails.stream().map(
                        PlnSchedulingTaskDetailEntity::getMaterialAvailableTime).filter(Objects::nonNull)
                .max(LocalDateTime::compareTo).orElse(null));
        target.setMaterialHardBlocked(sourceDetails.stream().anyMatch(item -> Boolean.TRUE.equals(
                item.getMaterialHardBlocked())) || merged.stream().anyMatch(item -> Boolean.TRUE.equals(
                item.getHardBlocked())));
        target.setMaterialConstraintStatus(Boolean.TRUE.equals(target.getMaterialHardBlocked())
                ? "MULTILEVEL_CONVERGENCE_CONFLICT" : merged.stream().anyMatch(item -> "WAITING_SUPPLY".equals(
                item.getStatus())) ? "WAITING_SUPPLY" : "READY");
        target.setMaterialConstraintSummary("合批来源物料约束已按工序取最晚汇合时刻；来源计划="
                + sourceDetails.size());
    }

    private PlnSchedulingTaskDetailEntity batchMaterialDetail(
            PlnSchedulingTaskDetailEntity primary,
            List<PlnSchedulingTaskDetailEntity> sourceDetails) {
        PlnSchedulingTaskDetailEntity result = new PlnSchedulingTaskDetailEntity();
        BeanUtils.copyProperties(primary, result);
        PlnIssueEntity merged = new PlnIssueEntity();
        applyBatchMaterialContext(merged, sourceDetails);
        result.setMaterialConstraintStatus(merged.getMaterialConstraintStatus());
        result.setMaterialAvailableDate(merged.getMaterialAvailableDate());
        result.setMaterialAvailableTime(merged.getMaterialAvailableTime());
        result.setMaterialHardBlocked(merged.getMaterialHardBlocked());
        result.setMaterialConstraintSummary(merged.getMaterialConstraintSummary());
        result.setOperationMaterialConstraintSnapshot(merged.getOperationMaterialConstraintSnapshot());
        result.setMaterialLineageFingerprint(merged.getMaterialLineageFingerprint());
        return result;
    }

    private OperationMaterialCatalog operationMaterialCatalog(String snapshot) {
        if (StrUtil.isBlank(snapshot)) return OperationMaterialCatalog.legacy();
        try {
            List<PlnOperationMaterialConstraintDTO> values = OBJECT_MAPPER.readValue(snapshot,
                    new TypeReference<List<PlnOperationMaterialConstraintDTO>>() {
                    });
            Map<String, PlnOperationMaterialConstraintDTO> byOperation = new LinkedHashMap<>();
            for (PlnOperationMaterialConstraintDTO value : Optional.ofNullable(values).orElse(List.of())) {
                if (value == null || StrUtil.isBlank(value.getProcessCode()) || StrUtil.isBlank(value.getRouteCode())
                        || StrUtil.isBlank(value.getEvidenceFingerprint())) {
                    throw new CheckedException("逐工序物料约束快照存在空工序或空证据指纹");
                }
                if (value.getSourceRouteNodeCode() != null
                        && !value.getSourceRouteNodeCode().matches("RN-[1-9][0-9]*"))
                    throw new CheckedException("逐工序物料约束快照的节点编号无效");
                if (byOperation.putIfAbsent(PlnRouteNodeSchedulingSupport.materialKey(value), value) != null) {
                    throw new CheckedException("逐工序物料约束快照的节点或旧工序[" + value.getProcessCode() + "]重复");
                }
            }
            if (byOperation.values().stream().anyMatch(value -> value.getSourceRouteNodeCode() == null)
                    && byOperation.values().stream().anyMatch(value -> value.getSourceRouteNodeCode() != null))
                throw new CheckedException("逐工序投料快照不能混用旧主工序与节点合同");
            return new OperationMaterialCatalog(byOperation, true);
        } catch (CheckedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CheckedException("逐工序物料约束快照无法解析，必须重新排产");
        }
    }

    private void applyOperationMaterial(PlnSchedulingOperationEntity operation,
                                        PlnOperationMaterialConstraintDTO constraint,
                                        String lineageFingerprint) {
        operation.setMaterialLineageFingerprint(lineageFingerprint);
        if (constraint == null) return;
        operation.setMaterialAvailableTime(constraintTime(constraint));
        operation.setMaterialConstraintStatus(constraint.getStatus());
        operation.setMaterialConstraintSummary(constraint.getSummary());
        operation.setMaterialConstraintSnapshot(toJson(constraint));
        operation.setMaterialConstraintFingerprint(constraint.getEvidenceFingerprint());
    }

    private LocalDateTime constraintTime(PlnOperationMaterialConstraintDTO constraint) {
        if (constraint == null) return null;
        if (constraint.getMaterialAvailableTime() != null) return constraint.getMaterialAvailableTime();
        return constraint.getMaterialAvailableDate() == null ? null
                : constraint.getMaterialAvailableDate().atTime(23, 59);
    }

    private String mergedTimePrecision(Collection<PlnOperationMaterialConstraintDTO> constraints) {
        if (constraints.stream().map(PlnOperationMaterialConstraintDTO::getMaterialTimePrecision)
                .anyMatch("DATE_END"::equals)) return "DATE_END";
        if (constraints.stream().map(PlnOperationMaterialConstraintDTO::getMaterialTimePrecision)
                .anyMatch("MINUTE"::equals)) return "MINUTE";
        if (constraints.stream().map(PlnOperationMaterialConstraintDTO::getMaterialTimePrecision)
                .anyMatch("INVENTORY_NOW"::equals)) return "INVENTORY_NOW";
        return null;
    }

    private String stableHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(StrUtil.nullToEmpty(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("逐工序物料证据指纹生成失败");
        }
    }

    private PlnSchedulingOperationEntity baseOperation(Long taskId, PlnIssueEntity issue,
                                                       MdmProcessRouteDTO route,
                                                       MdmProcessRouteWorkingVO working, int sequence) {
        PlnSchedulingOperationEntity operation = new PlnSchedulingOperationEntity();
        operation.setTaskId(taskId);
        operation.setIssueId(issue.getId());
        operation.setIssueNo(issue.getIssueNo());
        operation.setProductCode(issue.getProductCode());
        operation.setPlannedQuantity(issue.getNum());
        operation.setRouteId(route.getId());
        operation.setRouteCode(route.getRouteCode());
        operation.setRouteName(route.getRouteName());
        operation.setRouteWorkingId(working.getId());
        operation.setSourceRouteNodeCode(PlnRouteNodeSchedulingSupport.nodeCode(working.getId(), working.getRouteNodeCode()));
        operation.setOperationSeq(Optional.ofNullable(working.getOrderNum()).orElse(sequence));
        operation.setProcessCode(working.getProcessCode());
        operation.setProcessName(working.getProcessName());
        operation.setPredecessorCodes(String.join(",", normalizeRouteCodes(working.getPreRouteCode())));
        operation.setPredecessorNodeCodes(String.join(",", normalizeRouteCodes(working.getPreRouteNodeCodes())));
        operation.setMaterialLineageFingerprint(issue.getMaterialLineageFingerprint());
        operation.setLockStatus(false);
        operation.setFactoryCode(issue.getFactoryCode());
        return operation;
    }

    /**
     * 将路线前置关系校验并拓扑排序。具有画布快照的路线按显式DAG处理；没有图关系的历史路线
     * 保持按orderNum串行，避免升级后改变既有生产语义。
     */
    private RouteGraph resolveRouteGraph(MdmProcessRouteDTO route, List<MdmProcessRouteWorkingVO> source) {
        if (route.getId() == null || StrUtil.isBlank(route.getRouteCode()) || StrUtil.isBlank(route.getFactoryCode()))
            throw new CheckedException("路线缺少真实ID、编码或工厂范围");
        Map<String, MdmProcessRouteWorkingVO> byCode = new LinkedHashMap<>();
        Map<String, List<MdmProcessRouteWorkingVO>> byProcess = new LinkedHashMap<>();
        for (MdmProcessRouteWorkingVO working : source) {
            if (working == null || StrUtil.isBlank(working.getProcessCode())) {
                throw new CheckedException("存在空工序编码");
            }
            if (!Objects.equals(route.getFactoryCode(), working.getFactoryCode()))
                throw new CheckedException("路线节点工厂范围不一致");
            String code = PlnRouteNodeSchedulingSupport.nodeCode(working.getId(), working.getRouteNodeCode());
            working.setRouteNodeCode(code);
            byProcess.computeIfAbsent(working.getProcessCode(), ignored -> new ArrayList<>()).add(working);
            if (byCode.putIfAbsent(code, working) != null) {
                throw new CheckedException("路线节点[" + code + "]重复");
            }
        }
        boolean nodeGraph = source.stream().anyMatch(working -> working.getPreRouteNodeCodes() != null
                || working.getNextRouteNodeCodes() != null);
        if (nodeGraph && source.stream().anyMatch(working -> working.getPreRouteNodeCodes() == null
                || working.getNextRouteNodeCodes() == null))
            throw new CheckedException("路线节点关系合同不完整，不能混用主工序关系");
        if (!nodeGraph && byProcess.values().stream().anyMatch(values -> values.size() != 1))
            throw new CheckedException("旧主工序图存在重复工序，不能猜测节点关系");
        boolean explicitGraph = route.getProcedureSnapshot() != null || source.stream().anyMatch(working ->
                !normalizeRouteCodes(working.getPreRouteCode()).isEmpty()
                        || !normalizeRouteCodes(working.getNextRouteCode()).isEmpty());
        Map<String, List<String>> predecessors = new LinkedHashMap<>();
        List<MdmProcessRouteWorkingVO> orderFallback = source.stream().sorted(Comparator
                .comparing((MdmProcessRouteWorkingVO item) -> Optional.ofNullable(item.getOrderNum())
                        .orElse(Integer.MAX_VALUE))
                .thenComparing(item -> nodeGraph ? item.getRouteNodeCode() : item.getProcessCode())).toList();
        for (int index = 0; index < orderFallback.size(); index++) {
            MdmProcessRouteWorkingVO working = orderFallback.get(index);
            List<String> values = nodeGraph ? normalizeRouteCodes(working.getPreRouteNodeCodes())
                    : explicitGraph ? normalizeRouteCodes(working.getPreRouteCode()).stream().map(process -> {
                List<MdmProcessRouteWorkingVO> matches = byProcess.get(process);
                if (matches == null || matches.size() != 1)
                    throw new CheckedException("旧前置工序不能唯一对应节点：" + process);
                return matches.get(0).getRouteNodeCode();
            }).toList()
                    : index == 0 ? List.of() : List.of(orderFallback.get(index - 1).getRouteNodeCode());
            if (nodeGraph && values.size() != working.getPreRouteNodeCodes().length)
                throw new CheckedException("路线前置节点存在空值或重复值");
            for (String predecessor : values) {
                if (!byCode.containsKey(predecessor)) {
                    throw new CheckedException("工序[" + working.getProcessCode() + "]引用不存在的前置工序["
                            + predecessor + "]");
                }
                if (working.getRouteNodeCode().equals(predecessor)) {
                    throw new CheckedException("工序[" + working.getProcessCode() + "]存在自环");
                }
            }
            predecessors.put(working.getRouteNodeCode(), values);
        }
        Map<String, List<String>> successors = byCode.keySet().stream()
                .collect(Collectors.toMap(code -> code, code -> new ArrayList<>(), (a, b) -> a, LinkedHashMap::new));
        predecessors.forEach((code, values) -> values.forEach(predecessor -> successors.get(predecessor).add(code)));
        if (nodeGraph) for (MdmProcessRouteWorkingVO working : source) {
            List<String> declared = normalizeRouteCodes(working.getNextRouteNodeCodes());
            if (declared.size() != working.getNextRouteNodeCodes().length
                    || !new LinkedHashSet<>(declared).equals(new LinkedHashSet<>(successors.get(working.getRouteNodeCode()))))
                throw new CheckedException("路线前后置节点关系不一致：" + working.getRouteNodeCode());
        }
        List<String> terminalProcesses = successors.entrySet().stream().filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey).toList();
        if (terminalProcesses.size() != 1) {
            throw new CheckedException("必须且只能有一道最终工序，当前末端工序："
                    + String.join(",", terminalProcesses));
        }
        Map<String, Integer> indegrees = predecessors.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().size(),
                        (a, b) -> a, LinkedHashMap::new));
        PriorityQueue<String> ready = new PriorityQueue<>(Comparator
                .comparingInt((String code) -> Optional.ofNullable(byCode.get(code).getOrderNum())
                        .orElse(Integer.MAX_VALUE)).thenComparing(code -> nodeGraph ? code : byCode.get(code).getProcessCode()));
        indegrees.forEach((code, degree) -> {
            if (degree == 0) ready.add(code);
        });
        List<MdmProcessRouteWorkingVO> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String code = ready.poll();
            MdmProcessRouteWorkingVO working = byCode.get(code);
            working.setPreRouteNodeCodes(predecessors.get(code).toArray(String[]::new));
            working.setNextRouteNodeCodes(successors.get(code).toArray(String[]::new));
            // 独立冻结主工序投影，既有字段不承载RN身份。
            working.setPreRouteCode(predecessors.get(code).stream().map(node -> byCode.get(node).getProcessCode())
                    .distinct().toArray(String[]::new));
            working.setNextRouteCode(successors.get(code).stream().map(node -> byCode.get(node).getProcessCode())
                    .distinct().toArray(String[]::new));
            ordered.add(working);
            for (String successor : successors.get(code)) {
                int remaining = indegrees.computeIfPresent(successor, (key, degree) -> degree - 1);
                if (remaining == 0) ready.add(successor);
            }
        }
        if (ordered.size() != source.size()) {
            String cyclic = indegrees.entrySet().stream().filter(entry -> entry.getValue() > 0)
                    .map(Map.Entry::getKey).collect(Collectors.joining(","));
            throw new CheckedException("存在环路：" + cyclic);
        }
        boolean parallel = successors.values().stream().anyMatch(values -> values.size() > 1)
                || predecessors.values().stream().anyMatch(values -> values.size() > 1)
                || predecessors.values().stream().filter(List::isEmpty).count() > 1;
        return new RouteGraph(ordered, predecessors, parallel);
    }

    private List<String> normalizeRouteCodes(String[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        return Arrays.stream(values).map(StrUtil::trim).filter(StrUtil::isNotBlank).distinct().toList();
    }

    private boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private SchedulingInputs loadInputs(List<PlnIssueEntity> issues, LocalDate rangeStart) {
        RouteCatalog routes = loadRoutes(issues);
        ProcedureCatalog procedures = loadProcedures();
        List<MdmClassesEntity> shifts = loadShifts();
        Map<LocalDate, Boolean> workDays = loadWorkDays(rangeStart.minusDays(1), rangeStart.plusDays(MAX_CALENDAR_DAYS));
        LocalDateTime deviceRangeStart = rangeStart.minusDays(1).atStartOfDay();
        LocalDateTime deviceRangeEnd = rangeStart.plusDays(MAX_CALENDAR_DAYS).atTime(LocalTime.MAX);
        DeviceAvailabilityCatalog devices = loadDeviceAvailability(deviceCodes(routes), deviceRangeStart, deviceRangeEnd);
        WorkforceAvailabilityCatalog workforce = loadWorkforceAvailability(employeeCodes(routes), processCodes(routes),
                deviceRangeStart, deviceRangeEnd);
        ToolingAvailabilityCatalog tooling = loadToolingAvailability(routes, deviceRangeStart, deviceRangeEnd);
        ChangeoverCatalog changeover = loadChangeoverCatalog(issues, deviceRangeStart, deviceRangeEnd);
        DurationCalibrationCatalog durationCalibration = loadDurationCalibrationCatalog(issues, routes);
        return new SchedulingInputs(routes, procedures, devices, workforce, tooling, changeover, durationCalibration,
                new WorkingCalendar(rangeStart.minusDays(1), rangeStart.plusDays(MAX_CALENDAR_DAYS), shifts, workDays));
    }

    private DurationCalibrationCatalog loadDurationCalibrationCatalog(List<PlnIssueEntity> issues,
                                                                      RouteCatalog routes) {
        String factoryCode = issues.stream().map(PlnIssueEntity::getFactoryCode)
                .filter(StrUtil::isNotBlank).findFirst().orElse(null);
        if (StrUtil.isBlank(factoryCode)) return DurationCalibrationCatalog.unverified();
        try {
            MdmDurationCalibrationDTO.CatalogQuery query = new MdmDurationCalibrationDTO.CatalogQuery();
            query.setFactoryCode(factoryCode);
            query.setEffectiveTime(LocalDateTime.now());
            query.setProcessCodes(processCodes(routes));
            R<MdmDurationCalibrationDTO.Catalog> response = remoteMdmDurationCalibrationService.catalog(query);
            if (response == null || !ObjUtil.equals(CommonConstants.SUCCESS, response.getCode())
                    || response.getData() == null) return DurationCalibrationCatalog.unverified();
            return new DurationCalibrationCatalog(Optional.ofNullable(response.getData().getProfiles()).orElse(List.of()),
                    response.getData().getFingerprint(), true);
        } catch (Exception ex) {
            log.warn("[工序排程] 读取实际工时校准目录失败，factoryCode={}，原因:{}", factoryCode, ex.getMessage());
            return DurationCalibrationCatalog.unverified();
        }
    }

    private ChangeoverCatalog loadChangeoverCatalog(List<PlnIssueEntity> issues,
                                                    LocalDateTime start, LocalDateTime end) {
        List<String> products = issues.stream().map(PlnIssueEntity::getProductCode)
                .filter(StrUtil::isNotBlank).distinct().toList();
        if (products.isEmpty()) return ChangeoverCatalog.empty();
        try {
            MdmChangeoverCatalogQueryDTO query = new MdmChangeoverCatalogQueryDTO();
            query.setProductCodes(products);
            query.setFactoryCode(issues.stream().map(PlnIssueEntity::getFactoryCode)
                    .filter(StrUtil::isNotBlank).findFirst().orElse(null));
            query.setStartTime(start);
            query.setEndTime(end);
            R<MdmChangeoverCatalogDTO> response = remoteMdmChangeoverService.catalog(query);
            if (response == null || !ObjUtil.equals(CommonConstants.SUCCESS, response.getCode())
                    || response.getData() == null) {
                return ChangeoverCatalog.unverified();
            }
            MdmChangeoverCatalogDTO data = response.getData();
            return new ChangeoverCatalog(Optional.ofNullable(data.getFamilyByProduct()).orElse(Map.of()),
                    Optional.ofNullable(data.getFamilyNames()).orElse(Map.of()),
                    Optional.ofNullable(data.getRules()).orElse(List.of()), true,
                    Boolean.TRUE.equals(data.getGovernanceRequired()), start, end);
        } catch (Exception ex) {
            log.warn("[工序排程] 读取产品族与换型规则失败，products={}，原因:{}", products, ex.getMessage());
            return ChangeoverCatalog.unverified();
        }
    }

    private ToolingAvailabilityCatalog loadToolingAvailability(RouteCatalog routes,
                                                               LocalDateTime start, LocalDateTime end) {
        List<String> routeCodes = routes.values().stream().map(MdmProcessRouteDTO::getRouteCode)
                .filter(StrUtil::isNotBlank).distinct().toList();
        List<String> processes = processCodes(routes);
        if (routeCodes.isEmpty() || processes.isEmpty()) {
            return new ToolingAvailabilityCatalog(Map.of(), Map.of(), true, start, end);
        }
        try {
            MdmToolingAvailabilityQueryDTO query = new MdmToolingAvailabilityQueryDTO();
            query.setRouteCodes(routeCodes);
            query.setProcessCodes(processes);
            query.setRouteNodeCodes(routes.values().stream()
                    .flatMap(route -> Optional.ofNullable(route.getProcedureList()).orElse(List.of()).stream())
                    .map(working -> PlnRouteNodeSchedulingSupport.nodeCode(working.getId(), working.getRouteNodeCode()))
                    .distinct().toList());
            query.setStartTime(start);
            query.setEndTime(end);
            R<List<MdmToolingCandidateDTO>> response = remoteMdmToolingService.availability(query);
            if (response == null || !ObjUtil.equals(CommonConstants.SUCCESS, response.getCode())
                    || response.getData() == null) {
                return new ToolingAvailabilityCatalog(Map.of(), Map.of(), false, start, end);
            }
            Map<String, List<MdmToolingCandidateDTO>> candidates = new LinkedHashMap<>();
            for (MdmToolingCandidateDTO item : response.getData()) {
                if (item == null || StrUtil.isBlank(item.getRouteCode()) || StrUtil.isBlank(item.getProcessCode()))
                    throw new CheckedException("工装候选缺少真实路线或主工序身份");
                List<MdmProcessRouteDTO> matchingRoutes = routes.values().stream()
                        .filter(route -> Objects.equals(item.getRouteCode(), route.getRouteCode())).toList();
                if (matchingRoutes.size() != 1) throw new CheckedException("工装候选未唯一归属范围内路线");
                MdmProcessRouteWorkingVO working = PlnRouteNodeSchedulingSupport.working(
                        Optional.ofNullable(matchingRoutes.get(0).getProcedureList()).orElse(List.of()),
                        item.getRouteNodeCode(), item.getProcessCode(), null);
                String nodeCode = PlnRouteNodeSchedulingSupport.nodeCode(working.getId(), working.getRouteNodeCode());
                MdmProcedureToolingRequirementEntity requirement = item.getRequirementSnapshot();
                if (requirement == null || !Objects.equals(item.getRequirementId(), requirement.getId())
                        || !Objects.equals(item.getRouteCode(), requirement.getRouteCode())
                        || !Objects.equals(item.getProcessCode(), requirement.getProcessCode())
                        || requirement.getRouteNodeCode() != null && !Objects.equals(nodeCode, requirement.getRouteNodeCode()))
                    throw new CheckedException("工装要求与候选的路线节点身份不一致");
                MdmProcessRouteWorkingVO requirementWorking = PlnRouteNodeSchedulingSupport.working(
                        Optional.ofNullable(matchingRoutes.get(0).getProcedureList()).orElse(List.of()),
                        requirement.getRouteNodeCode(), requirement.getProcessCode(), null);
                if (!Objects.equals(nodeCode, PlnRouteNodeSchedulingSupport.nodeCode(
                        requirementWorking.getId(), requirementWorking.getRouteNodeCode()))
                        || !Objects.equals(matchingRoutes.get(0).getFactoryCode(), requirement.getFactoryCode()))
                    throw new CheckedException("工装要求不能唯一对应候选的原节点或工厂");
                candidates.computeIfAbsent(toolingOperationKey(item.getRouteCode(), nodeCode), ignored -> new ArrayList<>())
                        .add(item);
            }
            Map<String, List<MdmProcedureToolingRequirementEntity>> requirements = new LinkedHashMap<>();
            candidates.forEach((key, rows) -> requirements.put(key, rows.stream()
                    .map(MdmToolingCandidateDTO::getRequirementSnapshot).filter(Objects::nonNull)
                    .collect(Collectors.toMap(MdmProcedureToolingRequirementEntity::getId, item -> item,
                            (a, b) -> a, LinkedHashMap::new)).values().stream()
                    .sorted(Comparator.comparing(item -> Optional.ofNullable(item.getRequirementSeq()).orElse(Integer.MAX_VALUE)))
                    .toList()));
            return new ToolingAvailabilityCatalog(candidates, requirements, true, start, end);
        } catch (Exception e) {
            log.warn("[工序排程] 读取工装要求与可用性失败，routes={}，processes={}，原因:{}",
                    routeCodes, processes, e.getMessage());
            return new ToolingAvailabilityCatalog(Map.of(), Map.of(), false, start, end);
        }
    }

    private List<String> deviceCodes(RouteCatalog routes) {
        return routes.values().stream()
                .flatMap(route -> Optional.ofNullable(route.getProcedureList()).orElse(List.of()).stream())
                .flatMap(working -> Optional.ofNullable(working.getMdmProcessProcedureWorkList()).orElse(List.of()).stream())
                .flatMap(work -> work.getDeviceCodes() == null ? java.util.stream.Stream.empty()
                        : Arrays.stream(work.getDeviceCodes()))
                .filter(StrUtil::isNotBlank).distinct().toList();
    }

    private List<String> employeeCodes(RouteCatalog routes) {
        return routes.values().stream()
                .flatMap(route -> Optional.ofNullable(route.getProcedureList()).orElse(List.of()).stream())
                .flatMap(working -> Optional.ofNullable(working.getMdmProcessProcedureWorkList()).orElse(List.of()).stream())
                .flatMap(work -> work.getEmployeeCodes() == null ? java.util.stream.Stream.empty()
                        : Arrays.stream(work.getEmployeeCodes()))
                .filter(StrUtil::isNotBlank).distinct().toList();
    }

    private List<String> processCodes(RouteCatalog routes) {
        return routes.values().stream()
                .flatMap(route -> Optional.ofNullable(route.getProcedureList()).orElse(List.of()).stream())
                .map(MdmProcessRouteWorkingVO::getProcessCode).filter(StrUtil::isNotBlank).distinct().toList();
    }

    private WorkforceAvailabilityCatalog loadWorkforceAvailability(List<String> employeeCodes,
                                                                   List<String> processCodes, LocalDateTime start, LocalDateTime end) {
        if (CollUtil.isEmpty(processCodes)) {
            return new WorkforceAvailabilityCatalog(Map.of(), Map.of(), true, start, end);
        }
        try {
            List<MdmWorkforceCandidateDTO> rows = new ArrayList<>();
            List<String> employeeQueryCodes = employeeCodes.isEmpty() ? List.of() : employeeCodes;
            if (employeeQueryCodes.isEmpty()) {
                MdmWorkforceAvailabilityQueryDTO query = new MdmWorkforceAvailabilityQueryDTO();
                query.setEmployeeCodes(List.of());
                query.setProcessCodes(processCodes);
                query.setStartTime(start);
                query.setEndTime(end);
                R<List<MdmWorkforceCandidateDTO>> response = remoteMdmWorkforceService.availability(query);
                if (response == null || !ObjUtil.equals(CommonConstants.SUCCESS, response.getCode()) || response.getData() == null) {
                    return new WorkforceAvailabilityCatalog(Map.of(), Map.of(), false, start, end);
                }
                rows.addAll(response.getData());
            } else {
                for (int from = 0; from < employeeQueryCodes.size(); from += WORKFORCE_QUERY_BATCH_SIZE) {
                    MdmWorkforceAvailabilityQueryDTO query = new MdmWorkforceAvailabilityQueryDTO();
                    query.setEmployeeCodes(employeeQueryCodes.subList(from,
                            Math.min(employeeQueryCodes.size(), from + WORKFORCE_QUERY_BATCH_SIZE)));
                    query.setProcessCodes(processCodes);
                    query.setStartTime(start);
                    query.setEndTime(end);
                    R<List<MdmWorkforceCandidateDTO>> response = remoteMdmWorkforceService.availability(query);
                    if (response == null || !ObjUtil.equals(CommonConstants.SUCCESS, response.getCode()) || response.getData() == null) {
                        return new WorkforceAvailabilityCatalog(Map.of(), Map.of(), false, start, end);
                    }
                    rows.addAll(response.getData());
                }
            }
            Map<String, MdmWorkforceCandidateDTO> byCandidate = rows.stream()
                    .filter(row -> StrUtil.isNotBlank(row.getEmployeeCode()) && StrUtil.isNotBlank(row.getProcessCode()))
                    .collect(Collectors.toMap(row -> workforceCandidateKey(row.getEmployeeCode(), row.getProcessCode()),
                            row -> row, (a, b) -> a, LinkedHashMap::new));
            Map<String, List<MdmProcedureSkillRequirementEntity>> requirements = rows.stream()
                    .filter(row -> StrUtil.isNotBlank(row.getProcessCode()))
                    .collect(Collectors.toMap(MdmWorkforceCandidateDTO::getProcessCode,
                            row -> new ArrayList<>(Optional.ofNullable(row.getRequirementSnapshot()).orElse(List.of())),
                            (a, b) -> a.isEmpty() ? b : a, LinkedHashMap::new));
            return new WorkforceAvailabilityCatalog(byCandidate, requirements, true, start, end);
        } catch (Exception e) {
            log.warn("[工序排程] 读取人员能力与排班失败，employees={}，processes={}，原因:{}",
                    employeeCodes, processCodes, e.getMessage());
            return new WorkforceAvailabilityCatalog(Map.of(), Map.of(), false, start, end);
        }
    }

    private DeviceAvailabilityCatalog loadDeviceAvailability(List<String> deviceCodes,
                                                             LocalDateTime start, LocalDateTime end) {
        if (CollUtil.isEmpty(deviceCodes)) {
            return new DeviceAvailabilityCatalog(Map.of(), true, start, end);
        }
        try {
            List<TpmDeviceUnavailableWindowDTO> allWindows = new ArrayList<>();
            for (int from = 0; from < deviceCodes.size(); from += DEVICE_QUERY_BATCH_SIZE) {
                TpmDeviceAvailabilityQueryDTO query = new TpmDeviceAvailabilityQueryDTO();
                query.setDeviceCodes(deviceCodes.subList(from, Math.min(deviceCodes.size(), from + DEVICE_QUERY_BATCH_SIZE)));
                query.setStartTime(start);
                query.setEndTime(end);
                R<List<TpmDeviceUnavailableWindowDTO>> response = remoteTpmDeviceAvailabilityService.unavailableWindows(query);
                if (response == null || !ObjUtil.equals(CommonConstants.SUCCESS, response.getCode())
                        || response.getData() == null) {
                    return new DeviceAvailabilityCatalog(Map.of(), false, start, end);
                }
                allWindows.addAll(response.getData());
            }
            Map<String, List<TpmDeviceUnavailableWindowDTO>> byDevice = allWindows.stream()
                    .filter(item -> StrUtil.isNotBlank(item.getDeviceCode()) && item.getStartTime() != null)
                    .collect(Collectors.groupingBy(TpmDeviceUnavailableWindowDTO::getDeviceCode,
                            LinkedHashMap::new, Collectors.toList()));
            return new DeviceAvailabilityCatalog(byDevice, true, start, end);
        } catch (Exception e) {
            log.warn("[工序排程] 读取TPM设备可用性失败，devices={}，原因:{}", deviceCodes, e.getMessage());
            return new DeviceAvailabilityCatalog(Map.of(), false, start, end);
        }
    }

    private RouteCatalog loadRoutes(List<PlnIssueEntity> issues) {
        Map<String, MdmProcessRouteDTO> details = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        boolean verified = true;
        for (PlnIssueEntity issue : issues) {
            String key = routeKey(issue);
            if (!completeEngineeringBinding(issue)) {
                failures.put(key, "计划[" + Objects.toString(issue.getIssueNo(), String.valueOf(issue.getId()))
                        + "]未完整绑定已批准工程版本");
                continue;
            }
            try {
                MdmRouteEngineeringDTO.ProductionQuery query = new MdmRouteEngineeringDTO.ProductionQuery()
                        .setReleaseId(issue.getEngineeringReleaseId())
                        .setContentHash(issue.getEngineeringContentHash())
                        .setProductCode(issue.getProductCode());
                R<MdmRouteEngineeringDTO.ProductionPackage> response =
                        remoteMdmRouteEngineeringService.resolveForProduction(query);
                if (response == null || !response.isOk() || response.getData() == null) {
                    failures.put(key, "工程版本精确复核失败："
                            + (response == null ? "MDM无响应" : response.getMsg()));
                    continue;
                }
                MdmRouteEngineeringDTO.ProductionPackage resolved = response.getData();
                if (!sameEngineeringBinding(issue, resolved)) {
                    failures.put(key, "MDM返回的工程版本身份与计划快照不一致");
                    continue;
                }
                details.put(key, exactRoute(resolved));
            } catch (Exception e) {
                verified = false;
                failures.put(key, e instanceof CheckedException
                        ? e.getMessage() : "MDM工程版本服务不可用");
                log.warn("[工序排程] 精确复核工程版本失败，issueId={}，releaseId={}，原因:{}",
                        issue.getId(), issue.getEngineeringReleaseId(), e.getMessage());
            }
        }
        return new RouteCatalog(details, failures, verified);
    }

    /**
     * 只从MDM批准包原文构造APS路线，不在核完版本后回读今日路线。
     * 节点工作中心也取自同一包的nodes快照，避免工程发布后配置漂移或旧接口拒绝重复主工序。
     */
    private MdmProcessRouteDTO exactRoute(MdmRouteEngineeringDTO.ProductionPackage source) {
        try {
            if (source.getBundleSnapshot() == null
                    || !Objects.equals(source.getContentHash(), stableHash(source.getBundleSnapshot()))) {
                throw new CheckedException("工程版本原文或指纹缺失");
            }
            JsonNode root = OBJECT_MAPPER.readTree(source.getBundleSnapshot());
            JsonNode routeNode = root.path("route");
            JsonNode graph = root.path("graph");
            JsonNode procedures = graph.path("procedureList");
            JsonNode frozenNodes = root.path("nodes");
            if (!root.isObject() || !routeNode.isObject() || !graph.isObject()
                    || !procedures.isArray() || procedures.isEmpty() || procedures.size() > 200
                    || !frozenNodes.isArray() || frozenNodes.size() != procedures.size()
                    || !Objects.equals(source.getTenantId(), root.path("tenantId").asText())
                    || !Objects.equals(source.getFactoryCode(), root.path("factoryCode").asText())
                    || !Objects.equals(String.valueOf(source.getRouteId()), routeNode.path("id").asText())
                    || !Objects.equals(source.getRouteCode(), routeNode.path("routeCode").asText())
                    || !Objects.equals(source.getProductCode(), routeNode.path("productCode").asText())
                    || !Objects.equals(String.valueOf(source.getGraphRevision()), graph.path("graphRevision").asText())) {
                throw new CheckedException("工程版本内部路线图身份、组织或节点范围不完整");
            }
            MdmProcessRouteDTO route = OBJECT_MAPPER.treeToValue(routeNode, MdmProcessRouteDTO.class);
            route.setProcedureSnapshot(graph.path("procedureSnapshot").isMissingNode()
                    ? null : graph.path("procedureSnapshot"));
            List<MdmProcessRouteWorkingVO> workings = OBJECT_MAPPER.convertValue(procedures,
                    new TypeReference<List<MdmProcessRouteWorkingVO>>() {
                    });
            Map<String, JsonNode> byNode = new LinkedHashMap<>();
            for (JsonNode frozen : frozenNodes) {
                String nodeCode = frozen.path("routeNodeCode").asText();
                if (!nodeCode.matches("RN-[1-9][0-9]{0,18}")
                        || byNode.putIfAbsent(nodeCode, frozen) != null
                        || !frozen.path("works").isArray()) {
                    throw new CheckedException("工程版本节点执行资源快照缺失或重复");
                }
            }
            Set<String> seen = new LinkedHashSet<>();
            for (MdmProcessRouteWorkingVO working : workings) {
                JsonNode frozen = working == null ? null : byNode.get(working.getRouteNodeCode());
                if (frozen == null || !seen.add(working.getRouteNodeCode())
                        || !Objects.equals(working.getProcessCode(), frozen.path("processCode").asText())) {
                    throw new CheckedException("工程版本路线节点与执行资源快照不一致");
                }
                working.setMdmProcessProcedureWorkList(OBJECT_MAPPER.convertValue(frozen.path("works"),
                        new TypeReference<List<MdmProcessProcedureWorkVO>>() {
                        }));
            }
            if (!seen.equals(byNode.keySet())) {
                throw new CheckedException("工程版本节点执行资源范围不完整");
            }
            route.setProcedureList(workings);
            return route;
        } catch (CheckedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CheckedException("工程版本冻结路线无法解析，必须重新完成工程发布");
        }
    }

    private boolean completeEngineeringBinding(PlnIssueEntity issue) {
        return issue != null && issue.getEngineeringReleaseId() != null
                && StrUtil.isNotBlank(issue.getEngineeringContentHash())
                && issue.getEngineeringContentHash().matches("[0-9a-f]{64}")
                && issue.getEngineeringRouteId() != null && StrUtil.isNotBlank(issue.getEngineeringRouteCode())
                && issue.getEngineeringBomId() != null;
    }

    private boolean sameEngineeringBinding(PlnIssueEntity issue,
                                           MdmRouteEngineeringDTO.ProductionPackage resolved) {
        return Objects.equals(issue.getEngineeringReleaseId(), resolved.getReleaseId())
                && Objects.equals(issue.getEngineeringContentHash(), resolved.getContentHash())
                && Objects.equals(issue.getEngineeringVersionNo(), resolved.getVersionNo())
                && Objects.equals(issue.getEngineeringRouteId(), resolved.getRouteId())
                && Objects.equals(issue.getEngineeringRouteCode(), resolved.getRouteCode())
                && Objects.equals(issue.getEngineeringBomId(), resolved.getBomId())
                && Objects.equals(issue.getEngineeringBomVersion(), resolved.getBomVersion())
                && Objects.equals(issue.getFactoryCode(), resolved.getFactoryCode())
                && Objects.equals(issue.getProductCode(), resolved.getProductCode());
    }

    private void copyEngineeringBinding(PlnIssueEntity source, PlnSchedulingTaskDetailEntity target) {
        target.setEngineeringReleaseId(source.getEngineeringReleaseId());
        target.setEngineeringContentHash(source.getEngineeringContentHash());
        target.setEngineeringVersionNo(source.getEngineeringVersionNo());
        target.setEngineeringRouteId(source.getEngineeringRouteId());
        target.setProcessRouteCode(source.getEngineeringRouteCode());
        target.setEngineeringBomId(source.getEngineeringBomId());
        target.setEngineeringBomVersion(source.getEngineeringBomVersion());
    }

    private String routeKey(PlnIssueEntity issue) {
        return issue == null || issue.getId() == null ? "ISSUE:null" : "ISSUE:" + issue.getId();
    }

    private String routeKey(PlnSchedulingTaskDetailEntity detail) {
        return detail == null || detail.getIssueId() == null ? "ISSUE:null" : "ISSUE:" + detail.getIssueId();
    }

    private ProcedureCatalog loadProcedures() {
        try {
            R<List<MdmProcedureEntity>> response = remoteMdmProcedureService.getList();
            if (response == null || !ObjUtil.equals(CommonConstants.SUCCESS, response.getCode()) || response.getData() == null) {
                return new ProcedureCatalog(Map.of(), false);
            }
            return new ProcedureCatalog(response.getData().stream().filter(item -> StrUtil.isNotBlank(item.getProcessCode()))
                    .collect(Collectors.toMap(MdmProcedureEntity::getProcessCode, item -> item, (a, b) -> a)), true);
        } catch (Exception e) {
            log.warn("[工序排程] 读取工序标准时间失败，原因:{}", e.getMessage());
            return new ProcedureCatalog(Map.of(), false);
        }
    }

    private List<MdmClassesEntity> loadShifts() {
        try {
            R<List<MdmClassesEntity>> response = remoteClassesService.listActive();
            if (response != null && ObjUtil.equals(CommonConstants.SUCCESS, response.getCode())
                    && CollUtil.isNotEmpty(response.getData())) {
                return response.getData().stream()
                        .filter(item -> item.getStartTime() != null && item.getEndTime() != null)
                        .sorted(Comparator.comparing(MdmClassesEntity::getStartTime)).toList();
            }
        } catch (Exception e) {
            log.warn("[工序排程] 读取班次失败，降级为08:00-16:00，原因:{}", e.getMessage());
        }
        MdmClassesEntity fallback = new MdmClassesEntity();
        fallback.setShiftCode("APS_DEFAULT_SHIFT");
        fallback.setStartTime(DEFAULT_SHIFT_START);
        fallback.setEndTime(DEFAULT_SHIFT_END);
        return List.of(fallback);
    }

    private Map<LocalDate, Boolean> loadWorkDays(LocalDate start, LocalDate end) {
        try {
            R<List<MdmCalendarEntity>> response = remoteCalendarService.listByRange(start, end);
            if (response != null && ObjUtil.equals(CommonConstants.SUCCESS, response.getCode()) && response.getData() != null) {
                return response.getData().stream().filter(item -> item.getTime() != null)
                        .collect(Collectors.toMap(MdmCalendarEntity::getTime,
                                item -> "0".equals(item.getStatus()), (a, b) -> b));
            }
        } catch (Exception e) {
            log.warn("[工序排程] 读取工作日历失败，降级为周一至周五，原因:{}", e.getMessage());
        }
        return Map.of();
    }

    private Map<String, List<BookedInterval>> loadBookings(Long taskId, Long excludedIssueId,
                                                           LocalDateTime start, LocalDateTime end) {
        return loadBookings(taskId, excludedIssueId, start, end, null, null);
    }

    private Map<String, List<BookedInterval>> loadBookings(Long taskId, Long excludedIssueId,
                                                           LocalDateTime start, LocalDateTime end,
                                                           Long excludedLockedTaskId, Long excludedLockedIssueId) {
        List<PlnSchedulingOperationEntity> rows = operationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .isNotNull(PlnSchedulingOperationEntity::getScheduledStartTime)
                        .isNotNull(PlnSchedulingOperationEntity::getScheduledEndTime)
                        .lt(PlnSchedulingOperationEntity::getScheduledStartTime, end)
                        .gt(PlnSchedulingOperationEntity::getScheduledEndTime, start));
        Map<String, List<BookedInterval>> bookings = new LinkedHashMap<>();
        rows.stream().filter(item -> (Boolean.TRUE.equals(item.getLockStatus())
                        && !(Objects.equals(excludedLockedTaskId, item.getTaskId())
                        && Objects.equals(excludedLockedIssueId, item.getIssueId())))
                        || (Objects.equals(taskId, item.getTaskId()) && !Objects.equals(excludedIssueId, item.getIssueId())))
                .forEach(item -> operationKeys(item).forEach(key -> bookings.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new BookedInterval(item.getScheduledStartTime(), item.getScheduledEndTime(),
                                item.getTaskId(), item.getIssueId(), "APS_OPERATION", item.getIssueNo(),
                                item.getProductCode(), item.getSchedulingFamilyCode(), item.getProcessCode()))));
        bookings.values().forEach(items -> items.sort(Comparator.comparing(BookedInterval::start)));
        return bookings;
    }

    private Map<String, List<BookedInterval>> loadBookingsExcludingScope(
            Long proposalTaskId, Map<Long, Long> excludedFormalTasks,
            LocalDateTime start, LocalDateTime end) {
        List<PlnSchedulingOperationEntity> rows = operationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .isNotNull(PlnSchedulingOperationEntity::getScheduledStartTime)
                        .isNotNull(PlnSchedulingOperationEntity::getScheduledEndTime)
                        .lt(PlnSchedulingOperationEntity::getScheduledStartTime, end)
                        .gt(PlnSchedulingOperationEntity::getScheduledEndTime, start));
        Map<String, List<BookedInterval>> bookings = new LinkedHashMap<>();
        rows.stream().filter(item -> Boolean.TRUE.equals(item.getLockStatus())
                        && !scopeContains(excludedFormalTasks, item)
                        || Objects.equals(proposalTaskId, item.getTaskId()))
                .forEach(item -> operationKeys(item).forEach(key -> bookings.computeIfAbsent(key,
                                ignored -> new ArrayList<>())
                        .add(new BookedInterval(item.getScheduledStartTime(), item.getScheduledEndTime(),
                                item.getTaskId(), item.getIssueId(), "APS_OPERATION", item.getIssueNo(),
                                item.getProductCode(), item.getSchedulingFamilyCode(), item.getProcessCode()))));
        bookings.values().forEach(items -> items.sort(Comparator.comparing(BookedInterval::start)));
        return bookings;
    }

    private boolean scopeContains(Map<Long, Long> excludedFormalTasks,
                                  PlnSchedulingOperationEntity operation) {
        return operation != null && excludedFormalTasks != null
                && Objects.equals(excludedFormalTasks.get(operation.getIssueId()), operation.getTaskId());
    }

    private Map<String, List<BookedInterval>> operationBookings(List<PlnSchedulingOperationEntity> rows) {
        Map<String, List<BookedInterval>> result = new LinkedHashMap<>();
        rows.stream().filter(item -> item.getScheduledStartTime() != null && item.getScheduledEndTime() != null)
                .forEach(item -> operationKeys(item).forEach(key -> result.computeIfAbsent(key,
                                ignored -> new ArrayList<>())
                        .add(new BookedInterval(item.getScheduledStartTime(), item.getScheduledEndTime(),
                                item.getTaskId(), item.getIssueId(), "APS_OPERATION", item.getIssueNo(),
                                item.getProductCode(), item.getSchedulingFamilyCode(), item.getProcessCode()))));
        result.values().forEach(items -> items.sort(Comparator.comparing(BookedInterval::start)));
        return result;
    }

    private void addDeviceUnavailableBookings(Map<String, List<BookedInterval>> bookings,
                                              DeviceAvailabilityCatalog devices) {
        if (!devices.verified) {
            return;
        }
        devices.byDevice.forEach((deviceCode, windows) -> {
            List<BookedInterval> deviceBookings = bookings.computeIfAbsent(deviceKey(deviceCode), ignored -> new ArrayList<>());
            windows.forEach(window -> deviceBookings.add(new BookedInterval(window.getStartTime(),
                    window.getEndTime() == null ? devices.rangeEnd : window.getEndTime(), null, null,
                    window.getSourceType(), window.getReason(), null, null, null)));
            deviceBookings.sort(Comparator.comparing(BookedInterval::start));
        });
    }

    private void addToolingUnavailableBookings(Map<String, List<BookedInterval>> bookings,
                                               ToolingAvailabilityCatalog tooling) {
        if (!tooling.verified) {
            return;
        }
        Set<String> added = new HashSet<>();
        tooling.byOperation.values().stream().flatMap(Collection::stream)
                .filter(item -> StrUtil.isNotBlank(item.getToolingCode()))
                .forEach(item -> item.getUnavailableWindows().stream()
                        .filter(window -> window.getStartTime() != null && window.getEndTime() != null)
                        .forEach(window -> {
                            String identity = item.getToolingCode() + "|" + window.getStartTime() + "|"
                                    + window.getEndTime() + "|" + window.getWindowType();
                            if (!added.add(identity)) {
                                return;
                            }
                            List<BookedInterval> rows = bookings.computeIfAbsent(
                                    toolingKey(item.getToolingCode()), ignored -> new ArrayList<>());
                            rows.add(new BookedInterval(window.getStartTime(), window.getEndTime(), null, null,
                                    window.getWindowType(), window.getReason(), null, null, null));
                            rows.sort(Comparator.comparing(BookedInterval::start));
                        }));
    }

    private Map<String, BigDecimal> loadToolingLifeReservations(Long taskId, Long excludedIssueId) {
        return loadToolingLifeReservations(taskId, excludedIssueId, null, null);
    }

    private Map<String, BigDecimal> loadToolingLifeReservations(Long taskId, Long excludedIssueId,
                                                                Long excludedLockedTaskId,
                                                                Long excludedLockedIssueId) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        operationMapper.selectList(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .isNotNull(PlnSchedulingOperationEntity::getToolingLifeUsageSnapshot)).stream()
                .filter(item -> (Boolean.TRUE.equals(item.getLockStatus())
                        && !(Objects.equals(excludedLockedTaskId, item.getTaskId())
                        && Objects.equals(excludedLockedIssueId, item.getIssueId())))
                        || Objects.equals(taskId, item.getTaskId()) && !Objects.equals(excludedIssueId, item.getIssueId()))
                .forEach(item -> parseLifeUsage(item.getToolingLifeUsageSnapshot()).forEach(
                        (code, usage) -> result.merge(code, usage, BigDecimal::add)));
        return result;
    }

    private Map<String, BigDecimal> loadToolingLifeReservationsExcludingScope(
            Long proposalTaskId, Map<Long, Long> excludedFormalTasks) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        operationMapper.selectList(new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .isNotNull(PlnSchedulingOperationEntity::getToolingLifeUsageSnapshot)).stream()
                .filter(item -> Boolean.TRUE.equals(item.getLockStatus())
                        && !scopeContains(excludedFormalTasks, item)
                        || Objects.equals(proposalTaskId, item.getTaskId()))
                .forEach(item -> parseLifeUsage(item.getToolingLifeUsageSnapshot()).forEach(
                        (code, usage) -> result.merge(code, usage, BigDecimal::add)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, BigDecimal> parseLifeUsage(String snapshot) {
        if (StrUtil.isBlank(snapshot)) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = OBJECT_MAPPER.readValue(snapshot, Map.class);
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            raw.forEach((code, value) -> {
                if (StrUtil.isNotBlank(code) && value != null) {
                    result.put(code, new BigDecimal(String.valueOf(value)));
                }
            });
            return result;
        } catch (Exception ex) {
            throw new CheckedException("历史工装寿命预占快照损坏，禁止继续排产");
        }
    }

    private List<ToolingChoice> buildToolingChoices(ToolingAvailabilityCatalog catalog,
                                                    String routeCode, String routeNodeCode, String deviceCode, BigDecimal plannedQuantity,
                                                    DurationParts duration, Map<String, BigDecimal> lifeReservations) {
        List<MdmProcedureToolingRequirementEntity> requirements = catalog.requirements(routeCode, routeNodeCode).stream()
                .filter(item -> !Boolean.FALSE.equals(item.getMandatory())).toList();
        if (requirements.isEmpty()) {
            return List.of(ToolingChoice.empty());
        }
        List<ToolingChoice> choices = new ArrayList<>();
        choices.add(ToolingChoice.empty());
        for (MdmProcedureToolingRequirementEntity requirement : requirements) {
            BigDecimal usage = toolingUsage(requirement, plannedQuantity, duration);
            List<MdmToolingCandidateDTO> eligible = catalog.candidates(routeCode, routeNodeCode).stream()
                    .filter(item -> Objects.equals(requirement.getId(), item.getRequirementId()))
                    .filter(item -> Boolean.TRUE.equals(item.getQualified()) && StrUtil.isNotBlank(item.getToolingCode()))
                    .filter(item -> !Boolean.TRUE.equals(requirement.getDeviceCompatibilityRequired())
                            || StrUtil.isNotBlank(deviceCode) && item.getCompatibleDeviceCodes().contains(deviceCode))
                    .filter(item -> item.getRemainingLife() == null || item.getRemainingLife()
                            .subtract(lifeReservations.getOrDefault(item.getToolingCode(), BigDecimal.ZERO))
                            .compareTo(usage) >= 0)
                    .sorted(Comparator.comparing((MdmToolingCandidateDTO item) ->
                                    Optional.ofNullable(item.getRemainingLife()).orElse(new BigDecimal("999999999999")))
                            .reversed().thenComparing(MdmToolingCandidateDTO::getToolingCode)).toList();
            List<List<MdmToolingCandidateDTO>> combinations = combinations(eligible,
                    Optional.ofNullable(requirement.getRequiredQuantity()).orElse(1), 64);
            if (combinations.isEmpty()) {
                return List.of();
            }
            List<ToolingChoice> next = new ArrayList<>();
            for (ToolingChoice existing : choices) {
                for (List<MdmToolingCandidateDTO> combination : combinations) {
                    if (combination.stream().anyMatch(item -> existing.lifeUsage.containsKey(item.getToolingCode()))) {
                        continue;
                    }
                    next.add(existing.append(combination, usage));
                    if (next.size() >= 64) {
                        break;
                    }
                }
                if (next.size() >= 64) {
                    break;
                }
            }
            choices = next;
            if (choices.isEmpty()) {
                return choices;
            }
        }
        return choices;
    }

    private List<List<MdmToolingCandidateDTO>> combinations(List<MdmToolingCandidateDTO> source,
                                                            int required, int limit) {
        List<List<MdmToolingCandidateDTO>> result = new ArrayList<>();
        collectCombinations(source, required, 0, new ArrayList<>(), result, limit);
        return result;
    }

    private void collectCombinations(List<MdmToolingCandidateDTO> source, int required, int start,
                                     List<MdmToolingCandidateDTO> current, List<List<MdmToolingCandidateDTO>> result, int limit) {
        if (current.size() == required) {
            result.add(new ArrayList<>(current));
            return;
        }
        for (int i = start; i < source.size() && result.size() < limit; i++) {
            current.add(source.get(i));
            collectCombinations(source, required, i + 1, current, result, limit);
            current.remove(current.size() - 1);
        }
    }

    private BigDecimal toolingUsage(MdmProcedureToolingRequirementEntity requirement,
                                    BigDecimal plannedQuantity, DurationParts duration) {
        BigDecimal value = Optional.ofNullable(requirement.getUsageValue()).orElse(BigDecimal.ONE);
        String mode = StrUtil.blankToDefault(requirement.getUsageMode(), "FIXED").toUpperCase(Locale.ROOT);
        if ("PER_QUANTITY".equals(mode)) {
            return value.multiply(Optional.ofNullable(plannedQuantity).orElse(BigDecimal.ONE));
        }
        if ("PER_HOUR".equals(mode)) {
            return value.multiply(BigDecimal.valueOf(duration.runMinutes))
                    .divide(BigDecimal.valueOf(60), 6, RoundingMode.CEILING);
        }
        return value;
    }

    private ToolingBlock toolingBlock(ToolingAvailabilityCatalog catalog, String routeCode,
                                      String routeNodeCode, List<ResourceCandidate> resources, BigDecimal quantity,
                                      MdmProcedureEntity procedure, Map<String, BigDecimal> lifeReservations, long dailyMinutes,
                                      DurationCalibrationCatalog durationCalibration) {
        List<MdmToolingCandidateDTO> rows = catalog.candidates(routeCode, routeNodeCode);
        if (rows.stream().noneMatch(item -> StrUtil.isNotBlank(item.getToolingCode()))) {
            return new ToolingBlock(SchedulingOperationStatusEnum.TOOLING_MISSING.getCode(),
                    "没有配置满足工装要求的启用候选");
        }
        if (rows.stream().noneMatch(item -> Boolean.TRUE.equals(item.getQualified()))) {
            return new ToolingBlock(SchedulingOperationStatusEnum.TOOLING_UNQUALIFIED.getCode(),
                    "候选工装状态、校准、维护或已用寿命不满足：" + rows.stream()
                            .map(item -> StrUtil.blankToDefault(item.getToolingCode(), "未配置") + "："
                                    + item.getQualificationReason()).distinct().limit(5).collect(Collectors.joining("；")));
        }
        boolean compatibilityPossible = resources.stream().anyMatch(resource -> catalog.requirements(routeCode, routeNodeCode)
                .stream().filter(item -> !Boolean.FALSE.equals(item.getMandatory())).allMatch(requirement ->
                        !Boolean.TRUE.equals(requirement.getDeviceCompatibilityRequired())
                                || StrUtil.isNotBlank(resource.deviceCode) && rows.stream()
                                .filter(item -> Objects.equals(requirement.getId(), item.getRequirementId()))
                                .filter(item -> Boolean.TRUE.equals(item.getQualified()))
                                .anyMatch(item -> item.getCompatibleDeviceCodes().contains(resource.deviceCode))));
        if (!compatibilityPossible) {
            return new ToolingBlock(SchedulingOperationStatusEnum.TOOLING_BINDING_INVALID.getCode(),
                    "候选工装与可排设备的兼容关系不满足");
        }
        boolean lifePossible = resources.stream().anyMatch(resource -> {
            DurationParts duration = durationOf(quantity, procedure, resource, dailyMinutes, durationCalibration);
            return !buildToolingChoices(catalog, routeCode, routeNodeCode, resource.deviceCode,
                    quantity, duration, lifeReservations).isEmpty();
        });
        if (!lifePossible) {
            return new ToolingBlock(SchedulingOperationStatusEnum.TOOLING_LIFE_INSUFFICIENT.getCode(),
                    "候选工装数量或扣除既有计划预占后的剩余寿命不足");
        }
        return new ToolingBlock(SchedulingOperationStatusEnum.TOOLING_UNAVAILABLE.getCode(),
                "候选工装在计划范围内均被占用或处于维护、校准、维修窗口");
    }

    private String toolingSnapshot(ToolingAvailabilityCatalog catalog, String routeCode, String routeNodeCode,
                                   ToolingChoice choice, LocalDateTime start, LocalDateTime end) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("verified", catalog.verified);
        snapshot.put("queryStart", String.valueOf(catalog.rangeStart));
        snapshot.put("queryEnd", String.valueOf(catalog.rangeEnd));
        snapshot.put("scheduledStart", String.valueOf(start));
        snapshot.put("scheduledEnd", String.valueOf(end));
        snapshot.put("requirements", catalog.requirements(routeCode, routeNodeCode));
        snapshot.put("selected", choice.selected);
        snapshot.put("lifeUsage", choice.lifeUsage);
        return toJson(snapshot);
    }

    private void applyEvaluation(PlnIssueEntity issue, PlnOperationScheduleEvaluationDTO evaluation) {
        issue.setOperationScheduleStatus(evaluation.getStatus());
        issue.setOperationHardBlocked(evaluation.getHardBlocked());
        issue.setOperationScheduleSummary(evaluation.getSummary());
        issue.setOperationCount(evaluation.getOperationCount());
        issue.setOperationStartTime(evaluation.getFirstStartTime());
        issue.setOperationEndTime(evaluation.getLastEndTime());
        if (evaluation.getFirstStartTime() != null && evaluation.getLastEndTime() != null) {
            issue.setScheduledDate(evaluation.getFirstStartTime().toLocalDate());
            issue.setScheduledEndDate(evaluation.getLastEndTime().toLocalDate());
        }
    }

    private String sourceSnapshot(MdmProcedureEntity procedure, ResourceCandidate candidate) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("procedure", procedure);
        snapshot.put("selectedResource", candidate);
        return toJson(snapshot);
    }

    private String deviceSnapshot(DeviceAvailabilityCatalog devices, String deviceCode,
                                  LocalDateTime scheduledStart, LocalDateTime scheduledEnd) {
        if (StrUtil.isBlank(deviceCode)) {
            return null;
        }
        if (!devices.verified) {
            return "{\"verified\":false}";
        }
        List<TpmDeviceUnavailableWindowDTO> allWindows = devices.byDevice.getOrDefault(deviceCode, List.of());
        List<Map<String, Object>> windows = allWindows.stream()
                .filter(item -> item.getStartTime().isBefore(scheduledEnd.plusDays(1))
                        && (item.getEndTime() == null || item.getEndTime().isAfter(devices.rangeStart)))
                .limit(100)
                .map(this::windowSnapshot).toList();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("verified", true);
        snapshot.put("deviceCode", deviceCode);
        snapshot.put("queryStart", String.valueOf(devices.rangeStart));
        snapshot.put("queryEnd", String.valueOf(devices.rangeEnd));
        snapshot.put("scheduledStart", String.valueOf(scheduledStart));
        snapshot.put("scheduledEnd", String.valueOf(scheduledEnd));
        snapshot.put("totalUnavailableWindowCount", allWindows.size());
        snapshot.put("snapshotTruncated", allWindows.size() > windows.size());
        snapshot.put("unavailableWindows", windows);
        return toJson(snapshot);
    }

    private String deviceCandidatesSnapshot(List<ResourceCandidate> candidates,
                                            DeviceAvailabilityCatalog devices) {
        List<String> codes = candidates.stream().map(ResourceCandidate::deviceCode)
                .filter(StrUtil::isNotBlank).distinct().toList();
        List<Map<String, Object>> windows = codes.stream()
                .flatMap(code -> devices.byDevice.getOrDefault(code, List.of()).stream())
                .limit(100).map(this::windowSnapshot).toList();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("verified", devices.verified);
        snapshot.put("candidateDeviceCodes", codes);
        snapshot.put("queryStart", String.valueOf(devices.rangeStart));
        snapshot.put("queryEnd", String.valueOf(devices.rangeEnd));
        snapshot.put("unavailableWindows", windows);
        return toJson(snapshot);
    }

    private String deviceBlockingSummary(List<ResourceCandidate> candidates,
                                         DeviceAvailabilityCatalog devices) {
        return candidates.stream().map(ResourceCandidate::deviceCode).filter(StrUtil::isNotBlank).distinct()
                .map(code -> {
                    String reason = devices.byDevice.getOrDefault(code, List.of()).stream()
                            .map(TpmDeviceUnavailableWindowDTO::getReason).filter(StrUtil::isNotBlank)
                            .findFirst().orElse("无满足连续工时的空闲窗口");
                    return code + "：" + reason;
                }).limit(5).collect(Collectors.joining("；"));
    }

    private String workforceBlockingSummary(List<MdmWorkforceCandidateDTO> candidates) {
        if (candidates.isEmpty()) {
            return "没有可核验的候选员工";
        }
        return candidates.stream().map(candidate -> candidate.getEmployeeCode() + "："
                        + StrUtil.blankToDefault(candidate.getQualificationReason(), "无有效在岗窗口"))
                .distinct().limit(5).collect(Collectors.joining("；"));
    }

    private String workforceSnapshot(WorkforceAvailabilityCatalog workforce, String employeeCode,
                                     String processCode, LocalDateTime scheduledStart,
                                     LocalDateTime scheduledEnd) {
        if (StrUtil.isBlank(employeeCode)) {
            return null;
        }
        if (!workforce.verified) {
            return "{\"verified\":false}";
        }
        List<MdmWorkforceCandidateDTO> candidates = workforce.getAll(employeeCode, processCode);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("verified", true);
        snapshot.put("queryStart", String.valueOf(workforce.rangeStart));
        snapshot.put("queryEnd", String.valueOf(workforce.rangeEnd));
        snapshot.put("scheduledStart", String.valueOf(scheduledStart));
        snapshot.put("scheduledEnd", String.valueOf(scheduledEnd));
        snapshot.put("executionMode", candidates.size() > 1 ? "TEAM" : "SINGLE");
        snapshot.put("requiredHeadcount", workforce.requirements(processCode).stream()
                .map(MdmProcedureSkillRequirementEntity::getRequiredCount).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(workforce.requiresEmployee(processCode) ? 1 : 0));
        snapshot.put("requirements", workforce.requirements(processCode));
        snapshot.put("commonTeamCodes", commonTeamCodes(candidates));
        snapshot.put("candidates", candidates);
        return toJson(snapshot);
    }

    private Map<String, Object> windowSnapshot(TpmDeviceUnavailableWindowDTO item) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sourceType", item.getSourceType());
        snapshot.put("sourceCode", item.getSourceCode());
        snapshot.put("deviceCode", item.getDeviceCode());
        snapshot.put("deviceName", item.getDeviceName());
        snapshot.put("startTime", String.valueOf(item.getStartTime()));
        snapshot.put("endTime", item.getEndTime() == null ? null : String.valueOf(item.getEndTime()));
        snapshot.put("reason", item.getReason());
        return snapshot;
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CheckedException("工序排程快照序列化失败");
        }
    }

    private String durationProfileFingerprint(MdmDurationCalibrationProposalEntity profile) {
        if (profile == null) return null;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", profile.getId());
        values.put("versionNo", profile.getVersionNo());
        values.put("scopeType", profile.getScopeType());
        values.put("resourceCode", profile.getResourceCode());
        values.put("processCode", profile.getProcessCode());
        values.put("fixedSeconds", profile.getProposedFixedSeconds());
        values.put("unitSeconds", profile.getProposedUnitSeconds());
        values.put("effectiveTime", profile.getEffectiveTime());
        values.put("evidenceFingerprint", profile.getEvidenceFingerprint());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(toJson(values).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("无法生成实际工时校准参数指纹");
        }
    }

    private long positiveMinutes(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return value.setScale(0, RoundingMode.CEILING).longValue();
    }

    private long toMinutes(BigDecimal value, String unit) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        String normalized = StrUtil.blankToDefault(unit, "MINUTE").toUpperCase(Locale.ROOT);
        BigDecimal factor;
        if (normalized.contains("HOUR") || normalized.equals("H") || normalized.contains("小时")) {
            factor = BigDecimal.valueOf(60);
        } else if (normalized.contains("DAY") || normalized.equals("D") || normalized.contains("天")) {
            factor = BigDecimal.valueOf(480);
        } else if (normalized.contains("SECOND") || normalized.equals("S") || normalized.contains("秒")) {
            factor = BigDecimal.ONE.divide(BigDecimal.valueOf(60), 8, RoundingMode.HALF_UP);
        } else {
            factor = BigDecimal.ONE;
        }
        return Math.max(1, value.multiply(factor).setScale(0, RoundingMode.CEILING).longValue());
    }

    private LocalDateTime firstStart(List<PlnSchedulingOperationEntity> operations) {
        return operations.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
    }

    private LocalDateTime lastEnd(List<PlnSchedulingOperationEntity> operations) {
        return operations.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
    }

    private String resourceKey(PlnSchedulingOperationEntity operation) {
        Integer slotNo = operation.getResourceSlotNo() == null
                && "STATION".equalsIgnoreCase(operation.getResourceType()) ? 1 : operation.getResourceSlotNo();
        return StrUtil.blankToDefault(operation.getResourceType(), "UNKNOWN") + ":"
                + StrUtil.blankToDefault(operation.getResourceCode(), "UNKNOWN")
                + (slotNo == null ? "" : ":SLOT:" + slotNo);
    }

    private String deviceKey(String deviceCode) {
        return "DEVICE:" + deviceCode;
    }

    private String employeeKey(String employeeCode) {
        return "EMPLOYEE:" + employeeCode;
    }

    private String toolingKey(String toolingCode) {
        return "TOOLING:" + toolingCode;
    }

    private String resourceGroupKey(String groupCode, Integer slotNo) {
        return "RESOURCE_GROUP:" + groupCode + ":SLOT:" + slotNo;
    }

    private List<String> operationKeys(PlnSchedulingOperationEntity operation) {
        List<String> keys = new ArrayList<>();
        if (StrUtil.isNotBlank(operation.getResourceType()) && StrUtil.isNotBlank(operation.getResourceCode())) {
            keys.add(resourceKey(operation));
        }
        if (StrUtil.isNotBlank(operation.getDeviceCode())) {
            keys.add(deviceKey(operation.getDeviceCode()));
        }
        if (StrUtil.isNotBlank(operation.getResourceGroupCode()) && operation.getResourceGroupSlotNo() != null) {
            keys.add(resourceGroupKey(operation.getResourceGroupCode(), operation.getResourceGroupSlotNo()));
        }
        if (StrUtil.isNotBlank(operation.getEmployeeCode())) {
            csvList(operation.getEmployeeCode()).stream().map(this::employeeKey).forEach(keys::add);
        }
        if (StrUtil.isNotBlank(operation.getToolingCodes())) {
            Arrays.stream(operation.getToolingCodes().split(",")).map(String::trim)
                    .filter(StrUtil::isNotBlank).distinct().map(this::toolingKey).forEach(keys::add);
        }
        return keys;
    }

    private String candidateKey(PlnSchedulingOperationEntity operation) {
        return resourceKey(operation) + "|" + StrUtil.blankToDefault(operation.getDeviceCode(), "NO_DEVICE")
                + "|" + StrUtil.blankToDefault(operation.getEmployeeCode(), "NO_EMPLOYEE");
    }

    private record SchedulingInputs(RouteCatalog routes, ProcedureCatalog procedures,
                                    DeviceAvailabilityCatalog devices, WorkforceAvailabilityCatalog workforce,
                                    ToolingAvailabilityCatalog tooling, ChangeoverCatalog changeover,
                                    DurationCalibrationCatalog durationCalibration,
                                    WorkingCalendar calendar) {
    }

    private record DurationCalibrationCatalog(List<MdmDurationCalibrationProposalEntity> profiles,
                                              String fingerprint, boolean verified) {
        private static DurationCalibrationCatalog unverified() {
            return new DurationCalibrationCatalog(List.of(), null, false);
        }

        private DurationCalibrationResolution resolve(String processCode, ResourceCandidate candidate) {
            if (!verified || StrUtil.isBlank(processCode)) return DurationCalibrationResolution.none();
            List<MdmDurationCalibrationProposalEntity> matching = profiles.stream()
                    .filter(item -> Objects.equals(processCode, item.getProcessCode())).toList();
            if (candidate != null && StrUtil.isNotBlank(candidate.deviceCode)) {
                Optional<MdmDurationCalibrationProposalEntity> device = matching.stream()
                        .filter(item -> "DEVICE".equals(item.getScopeType()))
                        .filter(item -> Objects.equals(candidate.deviceCode, item.getResourceCode())).findFirst();
                if (device.isPresent())
                    return new DurationCalibrationResolution(device.get(), "DEVICE>STATION>PROCESS");
            }
            if (candidate != null && "STATION".equals(candidate.type)) {
                Optional<MdmDurationCalibrationProposalEntity> station = matching.stream()
                        .filter(item -> "STATION".equals(item.getScopeType()))
                        .filter(item -> Objects.equals(candidate.code, item.getResourceCode())).findFirst();
                if (station.isPresent()) return new DurationCalibrationResolution(station.get(), "STATION>PROCESS");
            }
            return matching.stream().filter(item -> "PROCESS".equals(item.getScopeType())).findFirst()
                    .map(item -> new DurationCalibrationResolution(item, "PROCESS"))
                    .orElseGet(DurationCalibrationResolution::none);
        }
    }

    private record DurationCalibrationResolution(MdmDurationCalibrationProposalEntity profile,
                                                 String precedence) {
        private static DurationCalibrationResolution none() {
            return new DurationCalibrationResolution(null, "MASTER_DATA");
        }
    }

    private record ChangeoverCatalog(Map<String, String> familyByProduct, Map<String, String> familyNames,
                                     List<MdmChangeoverRuleEntity> rules, boolean verified,
                                     boolean governanceRequired, LocalDateTime rangeStart, LocalDateTime rangeEnd) {
        private static ChangeoverCatalog empty() {
            return new ChangeoverCatalog(Map.of(), Map.of(), List.of(), true, false, null, null);
        }

        private static ChangeoverCatalog unverified() {
            return new ChangeoverCatalog(Map.of(), Map.of(), List.of(), false, false, null, null);
        }

        private String family(String productCode) {
            return familyByProduct.get(productCode);
        }

        private String familyName(String familyCode) {
            return familyNames.get(familyCode);
        }
    }

    private record RouteCatalog(Map<String, MdmProcessRouteDTO> byIssue,
                                Map<String, String> failures,
                                boolean verified) {
        private Collection<MdmProcessRouteDTO> values() {
            return byIssue.values().stream().collect(Collectors.toMap(MdmProcessRouteDTO::getId,
                    Function.identity(), (left, right) -> left, LinkedHashMap::new)).values();
        }
    }

    private record ProcedureCatalog(Map<String, MdmProcedureEntity> byCode, boolean verified) {
    }

    private record DeviceAvailabilityCatalog(Map<String, List<TpmDeviceUnavailableWindowDTO>> byDevice,
                                             boolean verified, LocalDateTime rangeStart, LocalDateTime rangeEnd) {
    }

    private record WorkforceAvailabilityCatalog(Map<String, MdmWorkforceCandidateDTO> byCandidate,
                                                Map<String, List<MdmProcedureSkillRequirementEntity>> requirementsByProcess,
                                                boolean verified, LocalDateTime rangeStart,
                                                LocalDateTime rangeEnd) {
        private boolean requiresEmployee(String processCode) {
            return CollUtil.isNotEmpty(requirementsByProcess.get(processCode));
        }

        private MdmWorkforceCandidateDTO get(String employeeCode, String processCode) {
            return byCandidate.get(workforceCandidateKey(employeeCode, processCode));
        }

        private List<MdmWorkforceCandidateDTO> getAll(String employeeCodes, String processCode) {
            if (StrUtil.isBlank(employeeCodes)) {
                return List.of();
            }
            return Arrays.stream(employeeCodes.split(",")).map(String::trim).filter(StrUtil::isNotBlank)
                    .distinct().map(code -> get(code, processCode)).filter(Objects::nonNull).toList();
        }

        private List<MdmProcedureSkillRequirementEntity> requirements(String processCode) {
            return requirementsByProcess.getOrDefault(processCode, List.of());
        }
    }

    private record ToolingAvailabilityCatalog(Map<String, List<MdmToolingCandidateDTO>> byOperation,
                                              Map<String, List<MdmProcedureToolingRequirementEntity>> requirementsByOperation,
                                              boolean verified, LocalDateTime rangeStart, LocalDateTime rangeEnd) {
        private boolean requiresTooling(String routeCode, String routeNodeCode) {
            return requirements(routeCode, routeNodeCode).stream()
                    .anyMatch(item -> !Boolean.FALSE.equals(item.getMandatory()));
        }

        private List<MdmProcedureToolingRequirementEntity> requirements(String routeCode, String routeNodeCode) {
            return requirementsByOperation.getOrDefault(toolingOperationKey(routeCode, routeNodeCode), List.of());
        }

        private List<MdmToolingCandidateDTO> candidates(String routeCode, String routeNodeCode) {
            return byOperation.getOrDefault(toolingOperationKey(routeCode, routeNodeCode), List.of());
        }
    }

    private record ResourceCandidate(String type, String code, String name, int priority,
                                     BigDecimal stationCapacity, BigDecimal standardWorkingTime,
                                     boolean fallback, String deviceCode, String deviceName,
                                     String employeeCode, String employeeName, Integer slotNo,
                                     String resourceGroupCode, String resourceGroupName,
                                     Integer resourceGroupCapacity, Integer resourceGroupSlotNo) {
        private ResourceCandidate(String type, String code, String name, int priority,
                                  BigDecimal stationCapacity, BigDecimal standardWorkingTime,
                                  boolean fallback, String deviceCode, String deviceName,
                                  String employeeCode, String employeeName, Integer slotNo) {
            this(type, code, name, priority, stationCapacity, standardWorkingTime, fallback,
                    deviceCode, deviceName, employeeCode, employeeName, slotNo, null, null, null, null);
        }

        private String key() {
            return type + ":" + code + (slotNo == null ? "" : ":SLOT:" + slotNo);
        }

        private boolean deviceBound() {
            return StrUtil.isNotBlank(deviceCode);
        }

        private boolean employeeBound() {
            return StrUtil.isNotBlank(employeeCode);
        }

        private boolean groupBound() {
            return StrUtil.isNotBlank(resourceGroupCode) && resourceGroupSlotNo != null;
        }

        private List<String> employeeCodes() {
            if (StrUtil.isBlank(employeeCode)) {
                return List.of();
            }
            return Arrays.stream(employeeCode.split(",")).map(String::trim)
                    .filter(StrUtil::isNotBlank).distinct().toList();
        }

        private List<String> keys() {
            List<String> result = new ArrayList<>();
            result.add(key());
            if (groupBound()) {
                result.add("RESOURCE_GROUP:" + resourceGroupCode + ":SLOT:" + resourceGroupSlotNo);
            }
            if (deviceBound()) {
                result.add("DEVICE:" + deviceCode);
            }
            if (employeeBound()) {
                employeeCodes().stream().map(code -> "EMPLOYEE:" + code).forEach(result::add);
            }
            return result;
        }

        private String candidateKey() {
            return key() + "|" + StrUtil.blankToDefault(deviceCode, "NO_DEVICE")
                    + "|" + StrUtil.blankToDefault(employeeCode, "NO_EMPLOYEE")
                    + "|" + StrUtil.blankToDefault(resourceGroupCode, "NO_GROUP")
                    + "|" + Optional.ofNullable(resourceGroupSlotNo).orElse(0);
        }


        private String baseCandidateKey() {
            return key() + "|" + StrUtil.blankToDefault(deviceCode, "NO_DEVICE")
                    + "|" + StrUtil.blankToDefault(resourceGroupCode, "NO_GROUP")
                    + "|" + Optional.ofNullable(resourceGroupSlotNo).orElse(0);
        }
    }

    private record RouteGraph(List<MdmProcessRouteWorkingVO> ordered,
                              Map<String, List<String>> predecessors, boolean parallel) {
    }

    private record OperationMaterialCatalog(Map<String, PlnOperationMaterialConstraintDTO> byOperation,
                                            boolean versioned) {
        private static OperationMaterialCatalog legacy() {
            return new OperationMaterialCatalog(Map.of(), false);
        }
    }

    private record DurationParts(long setupMinutes, long runMinutes,
                                 long disassemblyMinutes, long activeMinutes) {
        private DurationParts withChangeover(long changeoverMinutes) {
            long value = Math.max(0L, changeoverMinutes);
            return new DurationParts(setupMinutes + value, runMinutes, disassemblyMinutes,
                    activeMinutes + value);
        }
    }

    private record Allocation(LocalDateTime start, LocalDateTime end) {
    }

    private record ToolingChoice(List<MdmToolingCandidateDTO> selected, Map<String, BigDecimal> lifeUsage) {
        private static ToolingChoice empty() {
            return new ToolingChoice(List.of(), Map.of());
        }

        private ToolingChoice append(List<MdmToolingCandidateDTO> additions, BigDecimal usage) {
            List<MdmToolingCandidateDTO> nextSelected = new ArrayList<>(selected);
            nextSelected.addAll(additions);
            Map<String, BigDecimal> nextUsage = new LinkedHashMap<>(lifeUsage);
            additions.forEach(item -> nextUsage.merge(item.getToolingCode(), usage, BigDecimal::add));
            return new ToolingChoice(nextSelected, nextUsage);
        }

        private List<String> keys() {
            return selected.stream().map(MdmToolingCandidateDTO::getToolingCode)
                    .filter(StrUtil::isNotBlank).distinct().map(code -> "TOOLING:" + code).toList();
        }

        private String codes() {
            return selected.stream().map(MdmToolingCandidateDTO::getToolingCode).distinct()
                    .collect(Collectors.joining(","));
        }

        private String names() {
            return selected.stream().map(item -> StrUtil.blankToDefault(item.getToolingName(), item.getToolingCode()))
                    .distinct().collect(Collectors.joining(","));
        }
    }

    private record ToolingBlock(String status, String reason) {
    }

    private record ScopeResource(String scopeType, String resourceCode, String bookingKey) {
    }

    private record TransitionResolution(ChangeoverTransition transition, TransitionBlock block) {
        private static TransitionResolution ok(ChangeoverTransition transition) {
            return new TransitionResolution(transition, null);
        }

        private static TransitionResolution blocked(TransitionBlock block) {
            return new TransitionResolution(ChangeoverTransition.none(), block);
        }
    }

    private record AllocationChoice(ResourceCandidate candidate, DurationParts duration, Allocation allocation,
                                    ToolingChoice toolingChoice, ChangeoverTransition transition) {
    }

    private record BookedInterval(LocalDateTime start, LocalDateTime end, Long taskId, Long issueId,
                                  String sourceType, String reason, String productCode,
                                  String schedulingFamilyCode, String processCode) {
    }

    private record ChangeoverTransition(long minutes, List<MdmChangeoverRuleEntity> rules,
                                        Set<String> precedingProducts, Set<String> precedingFamilies) {
        private static ChangeoverTransition none() {
            return new ChangeoverTransition(0L, List.of(), Set.of(), Set.of());
        }
    }

    private record TransitionBlock(String status, String reason, String scopeType,
                                   String resourceCode, String fromFamilyCode, String toFamilyCode) {
    }

    private record TransitionAllocation(Allocation allocation, ChangeoverTransition transition,
                                        TransitionBlock block) {
        private static TransitionAllocation scheduled(Allocation allocation, ChangeoverTransition transition) {
            return new TransitionAllocation(allocation, transition, null);
        }

        private static TransitionAllocation blocked(TransitionBlock block) {
            return new TransitionAllocation(null, ChangeoverTransition.none(), block);
        }

        private static TransitionAllocation unavailable() {
            return new TransitionAllocation(null, ChangeoverTransition.none(), null);
        }
    }

    private record ValidationResult(String status, boolean hardBlocked, String summary) {
        private static ValidationResult blocked(String status, String summary) {
            return new ValidationResult(status, true, summary);
        }
    }

    private record PlanOutcome(List<PlnSchedulingOperationEntity> operations,
                               PlnOperationScheduleEvaluationDTO evaluation) {
        private static PlanOutcome blocked(Long issueId, String status, String summary) {
            return blocked(issueId, status, summary, List.of());
        }

        private static PlanOutcome blocked(Long issueId, String status, String summary,
                                           List<PlnSchedulingOperationEntity> operations) {
            return new PlanOutcome(operations, new PlnOperationScheduleEvaluationDTO()
                    .setIssueId(issueId).setStatus(status).setHardBlocked(true).setSummary(summary)
                    .setOperationCount(operations.size()));
        }
    }

    private record LocalReplanChoicePolicy(String strategy,
                                           List<PlnSchedulingOperationEntity> preferredOperations) {
    }

    /**
     * 把班次与工作日历预展开为不重叠的可执行时间窗，跨日班次也按班次开始日处理。
     */
    private static final class WorkingCalendar {
        private final List<TimeWindow> windows;
        private final long dailyMinutes;

        private WorkingCalendar(LocalDate start, LocalDate end, List<MdmClassesEntity> shifts,
                                Map<LocalDate, Boolean> workDays) {
            List<TimeWindow> raw = new ArrayList<>();
            long configuredDailyMinutes = 0;
            for (MdmClassesEntity shift : shifts) {
                long minutes = Duration.between(shift.getStartTime(), shift.getEndTime()).toMinutes();
                if (minutes <= 0) {
                    minutes += 24 * 60;
                }
                configuredDailyMinutes += minutes;
            }
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                boolean workDay = workDays.containsKey(date) ? workDays.get(date)
                        : date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
                if (!workDay) {
                    continue;
                }
                for (MdmClassesEntity shift : shifts) {
                    LocalDateTime shiftStart = date.atTime(shift.getStartTime());
                    LocalDateTime shiftEnd = date.atTime(shift.getEndTime());
                    if (!shiftEnd.isAfter(shiftStart)) {
                        shiftEnd = shiftEnd.plusDays(1);
                    }
                    raw.add(new TimeWindow(shiftStart, shiftEnd));
                }
            }
            raw.sort(Comparator.comparing(TimeWindow::start));
            this.windows = merge(raw);
            this.dailyMinutes = configuredDailyMinutes > 0 ? configuredDailyMinutes : 480;
        }

        private WorkingCalendar(List<TimeWindow> windows, long dailyMinutes) {
            this.windows = merge(windows.stream().sorted(Comparator.comparing(TimeWindow::start)).toList());
            this.dailyMinutes = dailyMinutes;
        }

        private static List<TimeWindow> merge(List<TimeWindow> raw) {
            List<TimeWindow> merged = new ArrayList<>();
            for (TimeWindow current : raw) {
                if (merged.isEmpty()) {
                    merged.add(current);
                    continue;
                }
                TimeWindow last = merged.get(merged.size() - 1);
                if (!current.start.isAfter(last.end)) {
                    merged.set(merged.size() - 1,
                            new TimeWindow(last.start, current.end.isAfter(last.end) ? current.end : last.end));
                } else {
                    merged.add(current);
                }
            }
            return merged;
        }

        private WorkingCalendar intersect(List<MdmWorkforceWindowDTO> workforceWindows) {
            List<TimeWindow> intersections = new ArrayList<>();
            for (TimeWindow base : windows) {
                for (MdmWorkforceWindowDTO workforce : workforceWindows) {
                    LocalDateTime start = base.start.isAfter(workforce.getStartTime())
                            ? base.start : workforce.getStartTime();
                    LocalDateTime end = base.end.isBefore(workforce.getEndTime())
                            ? base.end : workforce.getEndTime();
                    if (end.isAfter(start)) {
                        intersections.add(new TimeWindow(start, end));
                    }
                }
            }
            return new WorkingCalendar(intersections, dailyMinutes);
        }

        private LocalDateTime nextWorkingInstant(LocalDateTime value) {
            if (value == null) {
                return null;
            }
            for (TimeWindow window : windows) {
                if (!value.isAfter(window.end) && value.isAfter(window.start) && value.isBefore(window.end)) {
                    return value;
                }
                if (value.equals(window.start)) {
                    return value;
                }
                if (value.isBefore(window.start)) {
                    return window.start;
                }
            }
            return null;
        }

        private LocalDateTime addWorkingMinutes(LocalDateTime start, long minutes) {
            if (start == null || minutes <= 0) {
                return start;
            }
            long remaining = minutes;
            LocalDateTime cursor = nextWorkingInstant(start);
            if (cursor == null) {
                return null;
            }
            for (TimeWindow window : windows) {
                if (cursor.isAfter(window.end) || cursor.equals(window.end)) {
                    continue;
                }
                if (cursor.isBefore(window.start)) {
                    cursor = window.start;
                }
                if (cursor.isBefore(window.start) || !cursor.isBefore(window.end)) {
                    continue;
                }
                long available = Duration.between(cursor, window.end).toMinutes();
                if (remaining <= available) {
                    return cursor.plusMinutes(remaining);
                }
                remaining -= available;
                cursor = window.end;
            }
            return null;
        }

        private long dailyMinutes() {
            return dailyMinutes;
        }
    }

    private record TimeWindow(LocalDateTime start, LocalDateTime end) {
    }
}
