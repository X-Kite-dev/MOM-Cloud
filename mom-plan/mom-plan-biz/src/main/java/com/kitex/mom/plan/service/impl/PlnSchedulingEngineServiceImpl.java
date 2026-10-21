package com.kitex.mom.plan.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.constant.CommonConstants;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.entity.MdmCalendarEntity;
import com.kitex.mom.mdm.entity.MdmClassesEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteEntity;
import com.kitex.mom.mdm.feign.RemoteCalendarService;
import com.kitex.mom.mdm.feign.RemoteClassesService;
import com.kitex.mom.mdm.feign.RemoteProcessRouteService;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesApsBatchAllocationDTO;
import com.kitex.mom.mes.dto.MesTaskIssueReceiptDTO;
import com.kitex.mom.mes.dto.MesTaskReceiveDTO;
import com.kitex.mom.mes.dto.MesTaskReceiveRequestSigner;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.plan.constant.IssueStatusEnum;
import com.kitex.mom.plan.constant.ModuleEnum;
import com.kitex.mom.plan.constant.UpdateTypeEnum;
import com.kitex.mom.plan.dto.*;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.*;
import com.kitex.mom.plan.service.conflict.ConflictEngine;
import com.kitex.mom.plan.service.matcher.RuleMatchResult;
import com.kitex.mom.plan.service.matcher.RuleMatcher;
import com.kitex.mom.plan.service.selector.RuleSetSelector;
import com.kitex.mom.plan.vo.PlnResourceLoadVO;
import com.kitex.mom.wms.dto.WmsMaterialReservationLinkDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class PlnSchedulingEngineServiceImpl implements PlnSchedulingEngineService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 每天计划生产小时数（默认8小时工作制）
     */
    private static final int WORK_HOURS_PER_DAY = 8;
    /**
     * 计划开工时刻（当天 08:00）
     */
    private static final int PLAN_START_HOUR = 8;

    private final PlnSchedulingTaskMapper plnSchedulingTaskMapper;
    private final PlnSchedulingOperationMapper plnSchedulingOperationMapper;
    private final PlnSchedulingScenarioMapper plnSchedulingScenarioMapper;
    private final PlnApsCaseMapper plnApsCaseMapper;
    private final PlnObjectiveTemplateService objectiveTemplates;
    private final PlnProductDemandMapper plnProductDemandMapper;
    private final PlnIssueMapper plnIssueMapper;
    private final PlnSchedulingConflictMapper plnSchedulingConflictMapper;
    private final com.kitex.mom.plan.mapper.PlnSchedulingTaskDetailMapper plnSchedulingTaskDetailMapper;
    private final PlnSchedulingBatchMapper plnSchedulingBatchMapper;
    private final PlnSchedulingBatchAllocationMapper plnSchedulingBatchAllocationMapper;
    private final PlnUpdateLogService plnUpdateLogService;
    private final RemoteMesTaskService remoteTaskService;
    private final PlanMatMrpService planMatMrpService;
    private final PlnMaterialConstraintService materialConstraintService;
    private final PlnOperationSchedulingService operationSchedulingService;
    private final PlnResourceLoadService resourceLoadService;
    private final com.kitex.mom.plan.service.PlnSchedulingBatchService schedulingBatchService;
    private final RuleSetSelector ruleSetSelector;
    private final ConflictEngine conflictEngine;
    private final RemoteProcessRouteService remoteProcessRouteService;
    private final RemoteCalendarService remoteCalendarService;
    private final RemoteClassesService remoteClassesService;
    private final com.kitex.mom.plan.mapper.PlnSchedulingRuleSetMapper plnSchedulingRuleSetMapper;
    private final RuleMatcher ruleMatcher = new RuleMatcher();
    private final com.kitex.mom.plan.service.solver.SequenceSolverRegistry sequenceSolvers;
    private final com.kitex.mom.plan.mapper.PlnSchedulingSequenceProofMapper sequenceProofs;
    private final org.mybatis.spring.SqlSessionTemplate sqlSession;
    private final PlnApsFormalContractResolver formalContractResolver;

    @Override
    public PlnSchedulingRunInput captureRun(PlnSchedulingRunDTO original) {
        if (original == null) throw new CheckedException("运行参数不能为空");
        var dto = new PlnSchedulingRunDTO();
        org.springframework.beans.BeanUtils.copyProperties(original, dto);
        dto.setMode(StrUtil.blankToDefault(dto.getMode(), "simulate").toLowerCase(java.util.Locale.ROOT));
        dto.setCycleType(StrUtil.blankToDefault(dto.getCycleType(), "DAY").toUpperCase(java.util.Locale.ROOT));
        if ("T+N".equals(dto.getCycleType())) dto.setCycleType("TN");
        if (!Set.of("simulate", "official").contains(dto.getMode()) || !Set.of("DAY", "WEEK", "MONTH", "TN").contains(dto.getCycleType()))
            throw new CheckedException("运行模式或周期不合法");
        dto.setTriggerType("MANUAL");
        dto.setScheduleDate(resolveScheduleDate(dto));
        dto.setPlanNum("TN".equals(dto.getCycleType()) ? (dto.getPlanNum() == null ? defaultPlanNum() : dto.getPlanNum()) : null);
        if (dto.getPlanNum() != null && (dto.getPlanNum() < 1 || dto.getPlanNum() > 366))
            throw new CheckedException("排产范围须为1至366天");
        dto.setTargetIds(normalizeRunTargetIds(dto.getTargetIds()));
        var rows = listUnissuedPlansInRange(getCycleStartDate(dto.getCycleType(), dto.getScheduleDate()),
                getCycleEndDate(dto.getCycleType(), dto.getScheduleDate(), dto.getPlanNum()), dto.getTargetIds(), true);
        if (rows.isEmpty() || rows.size() > 1000)
            throw new CheckedException("当前受控运行须包含1至1000个计划；请明确分组，不截断来源");
        if (dto.getTargetIds() != null && rows.size() != dto.getTargetIds().size())
            throw new CheckedException("指定来源存在已锁定、已下达、越期或无权限计划，禁止丢失目标后继续求解");
        dto.setTargetIds(rows.stream().map(PlnIssueEntity::getId).sorted().toList());
        dto.setSequenceSolverCode(sequenceSolvers.normalize(dto.getSequenceSolverCode()));
        if (rows.size() > sequenceSolvers.specification(dto.getSequenceSolverCode()).maxPlans())
            throw new CheckedException("来源范围超过所选排序求解器上限，请显式选择策略或缩小来源");
        var input = new PlnSchedulingRunInput();
        input.setRun(dto);
        input.setCapturedDate(LocalDate.now());
        input.setSources(rows);
        input.setRule(requiredActiveRule());
        input.setSequenceSolverSpec(sequenceSolvers.specificationJson(dto.getSequenceSolverCode()));
        return input;
    }

    @Override
    public Boolean run(PlnSchedulingRunDTO runDTO) {
        com.kitex.mom.plan.service.SchedulingExecutionContext.check();
        if (runDTO == null) {
            throw new CheckedException("排产运行参数不能为空");
        }
        if ("simulate".equalsIgnoreCase(runDTO.getMode())) {
            return schedulingTaskOnly(runDTO);
        }
        if ("official".equalsIgnoreCase(runDTO.getMode())) {
            return writeOfficialIssue(runDTO);
        }
        throw new CheckedException("运行模式仅支持 simulate 或 official");
    }

    @Override
    public PlnSchedulingScenarioInput captureScenario(PlnSchedulingScenarioRunDTO original) {
        if (original == null) throw new CheckedException("多候选排产参数不能为空");
        sqlSession.clearCache(); // 同一事务多轮采集必须读取数据库，不能命中前轮一级缓存。
        var command = new PlnSchedulingScenarioRunDTO();
        org.springframework.beans.BeanUtils.copyProperties(original, command);
        command.setRequestId(null);
        command.setExpectedFingerprint(null);
        String cycle = StrUtil.blankToDefault(command.getCycleType(), "DAY").trim().toUpperCase(Locale.ROOT);
        if ("T+N".equals(cycle)) cycle = "TN";
        if (!Set.of("DAY", "WEEK", "MONTH", "TN").contains(cycle)) throw new CheckedException("排产周期无效");
        command.setCycleType(cycle);
        command.setScheduleDate(Objects.requireNonNullElse(command.getScheduleDate(), LocalDate.now()));
        command.setPlanNum("TN".equals(cycle) ? Objects.requireNonNullElseGet(command.getPlanNum(), this::defaultPlanNum) : null);
        if (command.getPlanNum() != null && (command.getPlanNum() < 1 || command.getPlanNum() > 366))
            throw new CheckedException("排产范围须为1至366天");
        command.setTriggerType(StrUtil.blankToDefault(command.getTriggerType(), "MANUAL").trim().toUpperCase(Locale.ROOT));
        command.setCandidateProfiles(command.getObjectiveTemplateId() == null
                ? normalizeCandidateProfiles(command.getCandidateProfiles())
                : objectiveTemplates.normalize(command.getCandidateProfiles()));
        // 浏览器JSON会去掉小数尾零；只在新快照规范化数值，不修改历史请求的永久指纹算法。
        for (var profile : command.getCandidateProfiles()) {
            profile.setDeliveryWeight(profile.getDeliveryWeight().stripTrailingZeros());
            profile.setChangeoverWeight(profile.getChangeoverWeight().stripTrailingZeros());
            profile.setCycleWeight(profile.getCycleWeight().stripTrailingZeros());
            profile.setWaitingWeight(profile.getWaitingWeight().stripTrailingZeros());
            profile.setLoadBalanceWeight(profile.getLoadBalanceWeight().stripTrailingZeros());
            profile.setWipWeight(profile.getWipWeight().stripTrailingZeros());
            profile.setOvertimeWeight(profile.getOvertimeWeight().stripTrailingZeros());
            profile.setDisturbanceWeight(profile.getDisturbanceWeight().stripTrailingZeros());
        }
        List<Long> requested = normalizeRunTargetIds(command.getTargetIds());
        var sources = listUnissuedPlansInRange(getCycleStartDate(cycle, command.getScheduleDate()),
                getCycleEndDate(cycle, command.getScheduleDate(), command.getPlanNum()), requested, true);
        if (sources.isEmpty() || sources.size() > 1000)
            throw new CheckedException("情景来源须为1至1000个可排计划，请明确分组，不截断");
        if (requested != null && sources.size() != requested.size())
            throw new CheckedException("指定来源含已锁定、已下达、越期或无权限计划，整组拒绝，不漏选后继续");
        String factory = com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode();
        Long tenant = com.kitex.common.data.tenant.TenantContextHolder.getTenantId();
        if (StrUtil.isBlank(factory) || tenant == null || sources.stream().anyMatch(p -> !Objects.equals(factory, p.getFactoryCode()) || !Objects.equals(tenant, p.getTenantId())))
            throw new CheckedException("情景来源须属于当前实际工厂及租户");
        if (StrUtil.isNotBlank(command.getFactoryCode()) && !factory.equals(command.getFactoryCode().trim()))
            throw new CheckedException("来源工厂与请求冻结工厂不同");
        command.setFactoryCode(factory);
        command.setTargetIds(sources.stream().map(PlnIssueEntity::getId).sorted().toList());
        Map<String, String> specs = new TreeMap<>();
        for (var profile : command.getCandidateProfiles()) {
            var spec = sequenceSolvers.specification(profile.getSequenceSolverCode());
            if (sources.size() > spec.maxPlans())
                throw new CheckedException("候选【" + profile.getCandidateName() + "】来源超过排序器上限" + spec.maxPlans());
            specs.put(profile.getCandidateCode(), sequenceSolvers.specificationJson(profile.getSequenceSolverCode()));
        }
        var input = new PlnSchedulingScenarioInput();
        input.setCapturedDate(LocalDate.now());
        input.setRun(command);
        input.setSources(sources.stream().sorted(Comparator.comparing(PlnIssueEntity::getId)).toList());
        input.setRule(requiredActiveRule());
        input.setSolverSpecs(specs);
        if (command.getObjectiveTemplateId() != null) {
            var template = objectiveTemplates.resolve(command.getObjectiveTemplateId(), command.getCandidateProfiles(),
                    command.getExpectedObjectiveTemplateFingerprint());
            input.setObjectiveTemplateSnapshot(com.kitex.mom.plan.service.solver.SequenceSolverRegistry.json(template));
        }
        scenarioInputJson(input); // 超限在预览时即拒绝，不到提交时才截断。
        return input;
    }

    private String scenarioInputJson(PlnSchedulingScenarioInput input) {
        String json = com.kitex.mom.plan.service.solver.SequenceSolverRegistry.json(input);
        if (json.getBytes(StandardCharsets.UTF_8).length > 4 * 1024 * 1024)
            throw new CheckedException("情景来源与规则快照超过4MiB，请明确拆组");
        return json;
    }

    @Override
    public PlnSchedulingScenarioDTO runScenario(PlnSchedulingScenarioRunDTO command) {
        if (command == null || StrUtil.isBlank(command.getRequestId())) {
            throw new CheckedException("多候选排产请求号不能为空");
        }
        String requestId = command.getRequestId().trim();
        if (requestId.length() > 64) throw new CheckedException("多候选排产请求号不能超过64位");
        String cycleType = StrUtil.blankToDefault(command.getCycleType(), "DAY").trim().toUpperCase();
        if ("T+N".equals(cycleType)) cycleType = "TN";
        if (!Set.of("DAY", "WEEK", "MONTH", "TN").contains(cycleType)) {
            throw new CheckedException("多候选排产周期仅支持DAY、WEEK、MONTH或TN");
        }
        command.setCycleType(cycleType);
        List<PlnSchedulingCandidateProfileDTO> profiles = normalizeCandidateProfiles(command.getCandidateProfiles());
        String requestFingerprint = scenarioRequestFingerprint(command, profiles);
        plnSchedulingScenarioMapper.lock("APS_SCENARIO_REQUEST:" + requestId);
        PlnSchedulingScenarioEntity existing = plnSchedulingScenarioMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                        .eq(PlnSchedulingScenarioEntity::getRequestId, requestId));
        if (existing != null) {
            if (!Objects.equals(existing.getFactoryCode(), com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode()))
                throw new CheckedException("原情景请求不属于当前实际工厂");
            if (!Objects.equals(existing.getRequestFingerprint(), requestFingerprint)) {
                throw new CheckedException("多候选排产请求号已使用且载荷不一致");
            }
            return scenarioView(existing);
        }

        var input = captureScenario(command);
        String inputSnapshot = scenarioInputJson(input);
        String inputFingerprint = sha256(inputSnapshot);
        if (command.getExpectedFingerprint() != null && !inputFingerprint.equals(command.getExpectedFingerprint()))
            throw new CheckedException("来源、规则、候选、算法版本或日期已变化，请重新预览整组情景");
        command = input.getRun();
        profiles = command.getCandidateProfiles();
        LocalDate baseDate = command.getScheduleDate();
        LocalDate startDate = getCycleStartDate(command.getCycleType(), baseDate);
        LocalDate endDate = getCycleEndDate(command.getCycleType(), baseDate, command.getPlanNum());
        List<PlnIssueEntity> sourcePlans = input.getSources();
        if (sourcePlans.isEmpty()) throw new CheckedException("当前范围没有可生成多候选方案的未锁定计划");
        Map<String, String> solverSpecs = new LinkedHashMap<>();
        for (var profile : profiles) {
            var spec = sequenceSolvers.specification(profile.getSequenceSolverCode());
            if (sourcePlans.size() > spec.maxPlans())
                throw new CheckedException("候选【" + profile.getCandidateName() + "】来源数量超过排序求解器上限" + spec.maxPlans() + "，整组不截断或降级");
            solverSpecs.put(profile.getCandidateCode(), sequenceSolvers.specificationJson(profile.getSequenceSolverCode()));
        }
        Set<String> factoryCodes = sourcePlans.stream().map(PlnIssueEntity::getFactoryCode)
                .filter(StrUtil::isNotBlank).map(String::trim).collect(Collectors.toSet());
        if (factoryCodes.size() != 1 || sourcePlans.stream().anyMatch(item -> StrUtil.isBlank(item.getFactoryCode()))) {
            throw new CheckedException("多候选排产必须且只能包含一个工厂，请补齐或按工厂拆分来源计划");
        }
        if (!factoryCodes.contains(com.kitex.common.data.orgFilter.FactoryContextHolder.getFactoryCode())
                || sourcePlans.stream().anyMatch(p -> !Objects.equals(p.getTenantId(), com.kitex.common.data.tenant.TenantContextHolder.getTenantId())))
            throw new CheckedException("多候选来源必须属于当前实际工厂及租户");
        if (StrUtil.isNotBlank(command.getFactoryCode())
                && !factoryCodes.contains(command.getFactoryCode().trim())) {
            throw new CheckedException("多候选排产来源计划不属于请求冻结工厂");
        }
        PlnSchedulingRuleSetEntity activeRule = input.getRule();
        LocalDate scheduleStart = resolveScheduleStartDate(command.getCycleType(), baseDate);
        List<Long> sourceIds = sourcePlans.stream().map(PlnIssueEntity::getId).sorted().toList();
        Map<Long, BaselinePlan> baselinePlans = sourcePlans.stream().collect(Collectors.toMap(
                PlnIssueEntity::getId,
                item -> new BaselinePlan(item.getScheduledDate()),
                (left, right) -> left, LinkedHashMap::new));
        String sourceFingerprint = sourceFingerprint(sourcePlans);
        String scenarioNo = buildScenarioNo(scheduleStart);

        PlnSchedulingScenarioEntity scenario = new PlnSchedulingScenarioEntity();
        scenario.setScenarioNo(scenarioNo);
        scenario.setRequestId(requestId);
        scenario.setRequestFingerprint(requestFingerprint);
        scenario.setTriggerType(StrUtil.blankToDefault(command.getTriggerType(), "MANUAL").toUpperCase());
        scenario.setCycleType(cycleType);
        scenario.setScheduleDate(scheduleStart);
        scenario.setPlanNum(command.getPlanNum());
        scenario.setSourceIssueIdsSnapshot(toJson(sourceIds));
        scenario.setSourceFingerprint(sourceFingerprint);
        scenario.setInputSnapshot(inputSnapshot);
        scenario.setInputFingerprint(inputFingerprint);
        scenario.setRuleCode(activeRule.getRuleCode());
        scenario.setRuleVersion(activeRule.getRuleVersion());
        scenario.setScenarioStatus("EVALUATING");
        scenario.setCandidateCount(profiles.size());
        scenario.setExpiresTime(LocalDateTime.now().plusHours(24));
        scenario.setFactoryCode(factoryCodes.iterator().next());
        if (plnSchedulingScenarioMapper.insert(scenario) != 1) {
            throw new CheckedException("多候选排产情景创建失败");
        }

        PlnSchedulingRunDTO run = new PlnSchedulingRunDTO();
        run.setMode("official");
        run.setTriggerType(scenario.getTriggerType());
        run.setCycleType(scenario.getCycleType());
        run.setScheduleDate(scheduleStart);
        run.setPlanNum(command.getPlanNum());
        run.setTargetIds(sourceIds);
        List<CandidateEvaluation> evaluations = new ArrayList<>();
        for (PlnSchedulingCandidateProfileDTO profile : profiles) {
            List<PlnIssueEntity> candidateSource = listUnissuedPlansInRange(startDate, endDate, sourceIds);
            if (candidateSource.size() != sourceIds.size() || !sourceFingerprint.equals(sourceFingerprint(candidateSource))) {
                throw new CheckedException("候选求解期间计划范围或数量/日期/状态事实变化，整组回滚，请重新发起情景");
            }
            if (!com.kitex.mom.plan.service.solver.SequenceSolverRegistry.json(input.getSources()).equals(
                    com.kitex.mom.plan.service.solver.SequenceSolverRegistry.json(candidateSource.stream().sorted(Comparator.comparing(PlnIssueEntity::getId)).toList())))
                throw new CheckedException("候选实际读取的完整来源与冻结事实不一致，整组回滚");
            if (!inputFingerprint.equals(sha256(scenarioInputJson(captureScenario(command)))))
                throw new CheckedException("候选间完整来源或规则快照变化，整组回滚，请重新预览");
            if (!solverSpecs.get(profile.getCandidateCode()).equals(sequenceSolvers.specificationJson(profile.getSequenceSolverCode())))
                throw new CheckedException("多候选求解器实现版本变化，不能在同组混用版本");
            List<PlnIssueEntity> scheduled = filterAndSortByRule(candidateSource, activeRule, scheduleStart, endDate,
                    profile.getSequenceStrategy(), profile.getCrossPriorityWindowDays(), profile.getSequenceSolverCode());
            if (scheduled.isEmpty())
                throw new CheckedException("候选【" + profile.getCandidateName() + "】规则过滤后无计划");
            PlnSchedulingTaskEntity task = buildTask(run, "EVALUATING", activeRule, scheduled.size(), scheduleStart);
            task.setScenarioId(scenario.getId());
            task.setCandidateCode(profile.getCandidateCode());
            task.setCandidateName(profile.getCandidateName());
            task.setSequenceStrategy(profile.getSequenceStrategy());
            task.setCrossPriorityWindowDays(profile.getCrossPriorityWindowDays());
            task.setObjectiveWeightsSnapshot(toJson(profile));
            task.setSelectedCandidate(false);
            task.setRemark("情景" + scenarioNo + "候选【" + profile.getCandidateName() + "】，完成全资源有限产能求解，待比较选择");
            if (plnSchedulingTaskMapper.insert(task) != 1) {
                throw new CheckedException("候选排产任务创建失败：" + profile.getCandidateName());
            }
            operationSchedulingService.scheduleTask(task.getId(), scheduled);
            snapshotTaskDetails(task.getId(), scheduled);
            schedulingBatchService.initializeSingleBatches(task.getId(), scheduled);
            CandidateMetrics metrics = candidateMetrics(task.getId(), baselinePlans);
            task.setCandidateMetricsSnapshot(toJson(metrics.toMap()));
            task.setCandidateFeasible(metrics.hardBlockedCount == 0);
            task.setEvaluatedTime(LocalDateTime.now());
            evaluations.add(new CandidateEvaluation(task, metrics, profile));
        }
        // 仅提交前锁定精确来源与所用规则，阻止复核后到提交之间被改写；不长期占用求解阶段的业务行锁。
        var lockedSources = plnIssueMapper.selectList(new LambdaQueryWrapper<PlnIssueEntity>()
                .in(PlnIssueEntity::getId, sourceIds).eq(PlnIssueEntity::getFactoryCode, command.getFactoryCode())
                .eq(PlnIssueEntity::getTenantId, sourcePlans.get(0).getTenantId())
                .orderByAsc(PlnIssueEntity::getId).last("FOR SHARE"));
        var lockedRule = plnSchedulingRuleSetMapper.selectOne(new LambdaQueryWrapper<PlnSchedulingRuleSetEntity>()
                .eq(PlnSchedulingRuleSetEntity::getId, activeRule.getId()).last("FOR SHARE"));
        if (lockedSources.size() != sourceIds.size() || lockedRule == null
                || !com.kitex.mom.plan.service.solver.SequenceSolverRegistry.json(input.getSources()).equals(
                com.kitex.mom.plan.service.solver.SequenceSolverRegistry.json(lockedSources))
                || !com.kitex.mom.plan.service.solver.SequenceSolverRegistry.json(activeRule).equals(
                com.kitex.mom.plan.service.solver.SequenceSolverRegistry.json(lockedRule))
                || !inputFingerprint.equals(sha256(scenarioInputJson(captureScenario(command)))))
            throw new CheckedException("提交前来源或规则变化，整组已回滚，请重新预览");
        if (!sourceFingerprint.equals(sourceFingerprint(listUnissuedPlansInRange(startDate, endDate, sourceIds))))
            throw new CheckedException("最后候选完成前来源事实变化，不能比较不同基线，整组已回滚");
        for (var profile : profiles)
            if (!solverSpecs.get(profile.getCandidateCode()).equals(sequenceSolvers.specificationJson(profile.getSequenceSolverCode())))
                throw new CheckedException("多候选结束时排序算法版本变化，整组已回滚");
        rankCandidates(evaluations);
        scenario.setScenarioStatus("EVALUATED");
        plnSchedulingScenarioMapper.updateById(scenario);
        return scenarioView(scenario);
    }

    @Override
    public PlnSchedulingScenarioDTO getScenario(String scenarioNo) {
        if (StrUtil.isBlank(scenarioNo)) throw new CheckedException("排产情景号不能为空");
        PlnSchedulingScenarioEntity scenario = plnSchedulingScenarioMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                        .eq(PlnSchedulingScenarioEntity::getScenarioNo, scenarioNo.trim()));
        if (scenario == null) throw new CheckedException("多候选排产情景不存在");
        return scenarioView(scenario);
    }

    @Override
    public PlnSchedulingScenarioDTO getScenarioById(Long scenarioId) {
        if (scenarioId == null) throw new CheckedException("排产情景ID不能为空");
        PlnSchedulingScenarioEntity scenario = plnSchedulingScenarioMapper.selectById(scenarioId);
        if (scenario == null) throw new CheckedException("多候选排产情景不存在");
        return scenarioView(scenario);
    }

    /**
     * 专业调整只产生同情景的新候选版本。原任务、工序、批次及初始排序证据保持不变；
     * 任何当前来源、资源或工序硬约束失败，整笔派生事务回滚，不能形成可选方案。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnSchedulingScenarioDTO deriveScenarioCandidate(
            Long caseId, PlnApsDecisionDTO.DeriveCommand command) {
        if (caseId == null || command == null || command.getOperations() == null
                || command.getOperations().isEmpty()) throw new CheckedException("专业调整参数不完整");
        if (StrUtil.isBlank(command.getRequestId()) || command.getRequestId().trim().length() > 64
                || StrUtil.isBlank(command.getReason()) || command.getReason().trim().length() > 500
                || command.getOperations().size() > 100)
            throw new CheckedException("专业调整请求号、原因或工序数量不符合约束");
        Long parentId = positiveId(command.getParentCandidateTaskId(), "父候选任务主键");
        String requestId = command.getRequestId().trim();
        String commandHash = sha256(JSONUtil.toJsonStr(command));
        PlnApsCaseEntity apsCase = plnApsCaseMapper.selectById(caseId);
        if (apsCase == null || !"EVALUATED".equals(apsCase.getCaseStatus())
                || !Objects.equals(apsCase.getContextFingerprint(), command.getExpectedCaseFingerprint())
                || apsCase.getScenarioId() == null) throw new CheckedException("APS案件已变化或不允许专业调整");
        PlnSchedulingScenarioEntity header = plnSchedulingScenarioMapper.selectById(apsCase.getScenarioId());
        if (header == null || !Objects.equals(header.getFactoryCode(), apsCase.getFactoryCode()))
            throw new CheckedException("案件情景与工厂不一致");
        plnSchedulingScenarioMapper.lock("APS_SCENARIO_SELECT:" + header.getScenarioNo());
        PlnSchedulingScenarioEntity scenario = plnSchedulingScenarioMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                        .eq(PlnSchedulingScenarioEntity::getId, header.getId()).last("FOR UPDATE"));
        if (scenario == null) throw new CheckedException("专业调整的排产情景不存在");
        PlnSchedulingTaskEntity replay = plnSchedulingTaskMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingTaskEntity>()
                        .eq(PlnSchedulingTaskEntity::getScenarioId, scenario.getId())
                        .eq(PlnSchedulingTaskEntity::getFactoryCode, scenario.getFactoryCode())
                        .eq(PlnSchedulingTaskEntity::getDerivationRequestId, requestId).last("LIMIT 1"));
        if (replay != null) {
            if (!Objects.equals(replay.getDerivationRequestFingerprint(), commandHash))
                throw new CheckedException("专业调整请求号已用于不同内容");
            return scenarioView(scenario);
        }
        if (!"EVALUATED".equals(scenario.getScenarioStatus())
                || scenario.getSelectedTaskId() != null
                || scenario.getExpiresTime() == null || !scenario.getExpiresTime().isAfter(LocalDateTime.now()))
            throw new CheckedException("情景已选案、过期或不能再派生候选");
        int derivedCount = Objects.requireNonNullElse(scenario.getDerivedCandidateCount(), 0);
        if (derivedCount >= 9) throw new CheckedException("同一情景最多保留9个专业调整版本，请新建案件重新求解");
        PlnSchedulingTaskEntity parent = plnSchedulingTaskMapper.selectById(parentId);
        if (parent == null || !Objects.equals(parent.getScenarioId(), scenario.getId())
                || !Objects.equals(parent.getFactoryCode(), scenario.getFactoryCode())
                || !"WAIT_CONFIRM".equals(parent.getTaskStatus())
                || !Boolean.TRUE.equals(parent.getCandidateFeasible())
                || Boolean.TRUE.equals(parent.getSelectedCandidate())
                || Boolean.TRUE.equals(parent.getResultArchived()))
            throw new CheckedException("父候选不属于可调整的当前情景，或存在硬阻断");
        PlnSchedulingRuleSetEntity activeRule = requiredActiveRule();
        if (!Objects.equals(activeRule.getRuleCode(), scenario.getRuleCode())
                || !Objects.equals(activeRule.getRuleVersion(), scenario.getRuleVersion()))
            throw new CheckedException("排产规则已变化，请重新求解后调整");
        List<Long> sourceIds = parseLongList(scenario.getSourceIssueIdsSnapshot());
        List<PlnIssueEntity> issues = plnIssueMapper.selectBatchIds(sourceIds);
        if (issues.size() != sourceIds.size()
                || issues.stream().anyMatch(issue -> !Objects.equals(issue.getFactoryCode(), scenario.getFactoryCode())
                || !Objects.equals(issue.getTenantId(), scenario.getTenantId()))
                || !Objects.equals(sourceFingerprint(issues), scenario.getSourceFingerprint()))
            throw new CheckedException("计划来源已变化，禁止基于旧候选调整");

        List<PlnSchedulingTaskDetailEntity> sourceDetails = plnSchedulingTaskDetailMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                        .eq(PlnSchedulingTaskDetailEntity::getTaskId, parentId));
        List<PlnSchedulingOperationEntity> sourceOperations = plnSchedulingOperationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getTaskId, parentId)
                        .orderByAsc(PlnSchedulingOperationEntity::getIssueId)
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq));
        List<PlnSchedulingBatchEntity> sourceBatches = plnSchedulingBatchMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingBatchEntity>()
                        .eq(PlnSchedulingBatchEntity::getTaskId, parentId));
        if (sourceDetails.size() != sourceIds.size() || sourceOperations.isEmpty()
                || sourceBatches.size() != sourceDetails.size()
                || sourceBatches.stream().anyMatch(batch -> !"SINGLE".equals(batch.getBatchType())
                || !Objects.equals(batch.getAllocationCount(), 1) || Boolean.TRUE.equals(batch.getLockStatus()))
                || sourceDetails.stream().anyMatch(detail -> Boolean.TRUE.equals(detail.getLockStatus()))
                || sourceOperations.stream().anyMatch(op -> Boolean.TRUE.equals(op.getLockStatus())))
            throw new CheckedException("父候选缺少完整单批次排程或已有执行锁；专业调整不能丢失批次数量账");
        schedulingBatchService.validateBeforeLock(parentId);

        PlnSchedulingTaskEntity derived = copyForInsert(parent, PlnSchedulingTaskEntity.class);
        derived.setTaskNo("APS-D-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT));
        derived.setCandidateCode("DERIVED_" + (derivedCount + 1));
        derived.setCandidateName("专业调整方案 " + (derivedCount + 1));
        derived.setTaskStatus("EVALUATING");
        derived.setExecutionId(null);
        derived.setResultArchived(false);
        derived.setLifecycleVersion(0);
        derived.setSelectedCandidate(false);
        derived.setCandidateMetricsSnapshot(null);
        derived.setCandidateScore(null);
        derived.setCandidateRank(null);
        derived.setCandidateFeasible(null);
        derived.setEvaluatedTime(null);
        derived.setParentCandidateTaskId(parentId);
        derived.setDerivationRequestId(requestId);
        derived.setDerivationRequestFingerprint(commandHash);
        derived.setDerivationReason(command.getReason().trim());
        derived.setDerivationDiffSnapshot("[]");
        derived.setRemark("由候选" + parent.getCandidateCode() + "派生；" + command.getReason().trim());
        if (plnSchedulingTaskMapper.insert(derived) != 1) throw new CheckedException("派生候选保存失败");

        Map<Long, PlnApsDecisionDTO.OperationAdjustment> edits = new LinkedHashMap<>();
        for (PlnApsDecisionDTO.OperationAdjustment edit : command.getOperations()) {
            Long operationId = positiveId(edit == null ? null : edit.getOperationId(), "父候选工序主键");
            if (edits.putIfAbsent(operationId, edit) != null) throw new CheckedException("同一道工序不能重复调整");
        }
        Set<Long> matched = new HashSet<>();
        List<Map<String, Object>> differences = new ArrayList<>();
        Map<Long, List<PlnSchedulingOperationEntity>> derivedByIssue = new LinkedHashMap<>();
        for (PlnSchedulingOperationEntity source : sourceOperations) {
            PlnSchedulingOperationEntity operation = copyForInsert(source, PlnSchedulingOperationEntity.class);
            operation.setTaskId(derived.getId());
            operation.setSchedulingBatchId(null);
            operation.setSchedulingBatchNo(null);
            operation.setSchedulingBatchType(null);
            operation.setBatchAllocationSnapshot(null);
            operation.setLockStatus(false);
            operation.setLockTime(null);
            PlnApsDecisionDTO.OperationAdjustment edit = edits.get(source.getId());
            if (edit != null) {
                matched.add(source.getId());
                applyProfessionalEdit(source, operation, edit, differences);
            }
            if (plnSchedulingOperationMapper.insert(operation) != 1)
                throw new CheckedException("派生候选工序复制失败");
            derivedByIssue.computeIfAbsent(operation.getIssueId(), ignored -> new ArrayList<>()).add(operation);
        }
        if (matched.size() != edits.size() || differences.isEmpty())
            throw new CheckedException("调整工序不属于父候选，或没有实际时刻/资源变化");
        derived.setDerivationDiffSnapshot(toJson(differences));
        plnSchedulingTaskMapper.updateById(derived);
        List<PlnSchedulingTaskDetailEntity> details = new ArrayList<>();
        for (PlnSchedulingTaskDetailEntity source : sourceDetails) {
            PlnSchedulingTaskDetailEntity detail = copyForInsert(source, PlnSchedulingTaskDetailEntity.class);
            detail.setTaskId(derived.getId());
            detail.setLockStatus(false);
            detail.setLockTaskId(null);
            detail.setLockTime(null);
            List<PlnSchedulingOperationEntity> operations = derivedByIssue
                    .getOrDefault(source.getIssueId(), List.of());
            LocalDateTime first = operations.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                    .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
            LocalDateTime last = operations.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                    .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
            if (first == null || last == null) throw new CheckedException("派生候选存在未排时工序");
            detail.setScheduledDate(first.toLocalDate());
            detail.setScheduledEndDate(last.toLocalDate());
            detail.setOperationStartTime(first);
            detail.setOperationEndTime(last);
            if (plnSchedulingTaskDetailMapper.insert(detail) != 1)
                throw new CheckedException("派生候选计划明细复制失败");
            details.add(detail);
        }
        schedulingBatchService.initializeSingleBatches(derived.getId(), issues);
        refreshMaterialConstraints(details, LocalDate.now());
        schedulingBatchService.validateBeforeLock(derived.getId());
        operationSchedulingService.validateDerivedCalendar(derived.getId());
        operationSchedulingService.validateBeforeLock(derived.getId(), details);
        List<PlnSchedulingConflictEntity> conflicts = conflictEngine.detect(derived.getId(), details);
        if (details.stream().anyMatch(item -> Boolean.TRUE.equals(item.getMaterialHardBlocked())
                || Boolean.TRUE.equals(item.getOperationHardBlocked()))
                || conflicts.stream().anyMatch(item -> Boolean.TRUE.equals(item.getHardConstraint()))) {
            String explanation = details.stream().filter(item -> Boolean.TRUE.equals(item.getOperationHardBlocked()))
                    .map(PlnSchedulingTaskDetailEntity::getOperationScheduleSummary)
                    .filter(StrUtil::isNotBlank).findFirst()
                    .orElseGet(() -> conflicts.stream().filter(item -> Boolean.TRUE.equals(item.getHardConstraint()))
                            .map(PlnSchedulingConflictEntity::getConflictContent).findFirst()
                            .orElse("存在物料、资源或工序硬冲突"));
            throw new CheckedException("专业调整未通过硬约束校验：" + explanation);
        }
        for (PlnSchedulingConflictEntity conflict : conflicts)
            plnSchedulingConflictMapper.insert(conflict);
        cloneSequenceProof(parentId, derived);
        Map<Long, BaselinePlan> baseline = issues.stream().collect(Collectors.toMap(
                PlnIssueEntity::getId, issue -> new BaselinePlan(issue.getScheduledDate())));
        List<CandidateEvaluation> evaluations = new ArrayList<>();
        List<PlnSchedulingTaskEntity> allCandidates = scenarioTasks(scenario.getId());
        if (allCandidates.stream().anyMatch(task -> !"WAIT_CONFIRM".equals(task.getTaskStatus())
                && !Objects.equals(task.getId(), derived.getId())))
            throw new CheckedException("情景中存在已取消或已冻结的候选，不能重新比较");
        for (PlnSchedulingTaskEntity task : allCandidates) {
            CandidateMetrics metrics = candidateMetrics(task.getId(), baseline);
            PlnSchedulingCandidateProfileDTO profile = candidateProfile(task.getObjectiveWeightsSnapshot());
            task.setCandidateFeasible(metrics.hardBlockedCount == 0);
            task.setEvaluatedTime(LocalDateTime.now());
            evaluations.add(new CandidateEvaluation(task, metrics, profile));
        }
        rankCandidates(evaluations);
        scenario.setDerivedCandidateCount(derivedCount + 1);
        plnSchedulingScenarioMapper.updateById(scenario);
        return scenarioView(scenario);
    }

    private Long positiveId(String value, String label) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (RuntimeException ignored) {
            // 统一返回领域错误，不把格式异常泄漏给前端。
        }
        throw new CheckedException(label + "必须是正整数");
    }

    private <T extends com.kitex.common.core.entity.BaseEntity<T>> T copyForInsert(T source, Class<T> type) {
        try {
            T target = type.getDeclaredConstructor().newInstance();
            org.springframework.beans.BeanUtils.copyProperties(source, target,
                    "id", "createBy", "createTime", "updateBy", "updateTime");
            return target;
        } catch (ReflectiveOperationException exception) {
            throw new CheckedException("排产快照复制失败");
        }
    }

    private void applyProfessionalEdit(PlnSchedulingOperationEntity source,
                                       PlnSchedulingOperationEntity target,
                                       PlnApsDecisionDTO.OperationAdjustment edit,
                                       List<Map<String, Object>> differences) {
        LocalDateTime oldStart = source.getScheduledStartTime();
        LocalDateTime oldEnd = source.getScheduledEndTime();
        if (oldStart == null || oldEnd == null || !oldEnd.isAfter(oldStart))
            throw new CheckedException("父候选工序没有可移动的有效时间窗口");
        if (edit.getScheduledStartTime() == null && oldStart.isBefore(LocalDateTime.now()))
            throw new CheckedException("历史时段工序不能只换资源；请明确新的未来开工时刻");
        if (edit.getScheduledStartTime() != null) {
            LocalDateTime start = edit.getScheduledStartTime();
            if (start.isBefore(LocalDateTime.now()) || start.isAfter(LocalDateTime.now().plusDays(366)))
                throw new CheckedException("专业调整只能安排未来366天内尚未执行的工序");
            Duration delta = Duration.between(oldStart, start);
            target.setScheduledStartTime(start);
            target.setScheduledEndTime(oldEnd.plus(delta));
            if (source.getTransferReadyTime() != null)
                target.setTransferReadyTime(source.getTransferReadyTime().plus(delta));
        }
        if (edit.getResourceChoiceIndex() != null) {
            if (StrUtil.isBlank(source.getCandidateResourceSnapshot()))
                throw new CheckedException("父候选没有可核验的许可资源快照，不能切换资源");
            try {
                JsonNode options = OBJECT_MAPPER.readTree(source.getCandidateResourceSnapshot());
                int index = edit.getResourceChoiceIndex();
                if (!options.isArray() || index < 0 || index >= options.size())
                    throw new CheckedException("目标资源不在父候选冻结的许可资源目录内");
                JsonNode choice = options.get(index);
                if (!choice.isObject() || !choice.path("type").isTextual()
                        || !choice.path("code").isTextual())
                    throw new CheckedException("目标资源组合缺少类型或编码，不能应用");
                JsonNode current = null;
                for (JsonNode option : options) {
                    if (Objects.equals(option.path("type").asText(null), source.getResourceType())
                            && Objects.equals(option.path("code").asText(null), source.getResourceCode())
                            && Objects.equals(nullableInt(option, "slotNo"), source.getResourceSlotNo())
                            && Objects.equals(option.path("resourceGroupCode").asText(null),
                                    source.getResourceGroupCode())
                            && Objects.equals(nullableInt(option, "resourceGroupSlotNo"),
                                    source.getResourceGroupSlotNo())
                            && Objects.equals(option.path("deviceCode").asText(null), source.getDeviceCode())
                            && Objects.equals(option.path("employeeCode").asText(null), source.getEmployeeCode())) {
                        current = option;
                        break;
                    }
                }
                if (current == null || !Objects.equals(current.path("type").asText(null),
                        choice.path("type").asText(null))
                        || !sameDurationInput(current, choice, "stationCapacity")
                        || !sameDurationInput(current, choice, "standardWorkingTime"))
                    throw new CheckedException("切换资源会改变工时/产能计算，须重新求解生成候选，不能仅复制旧工序时长");
                target.setResourceType(choice.path("type").asText(null));
                target.setResourceCode(choice.path("code").asText(null));
                target.setResourceName(choice.path("name").asText(null));
                target.setResourceSlotNo(nullableInt(choice, "slotNo"));
                target.setResourceGroupCode(choice.path("resourceGroupCode").asText(null));
                target.setResourceGroupName(choice.path("resourceGroupName").asText(null));
                target.setResourceGroupCapacity(nullableInt(choice, "resourceGroupCapacity"));
                target.setResourceGroupSlotNo(nullableInt(choice, "resourceGroupSlotNo"));
                target.setDeviceCode(choice.path("deviceCode").asText(null));
                target.setDeviceName(choice.path("deviceName").asText(null));
                target.setEmployeeCode(choice.path("employeeCode").asText(null));
                target.setEmployeeName(choice.path("employeeName").asText(null));
                target.setDeviceAvailabilitySnapshot(null);
                target.setWorkforceAvailabilitySnapshot(null);
            } catch (JsonProcessingException exception) {
                throw new CheckedException("父候选许可资源快照损坏，不能切换资源");
            }
        }
        boolean changed = !Objects.equals(oldStart, target.getScheduledStartTime())
                || !Objects.equals(source.getResourceType(), target.getResourceType())
                || !Objects.equals(source.getResourceCode(), target.getResourceCode())
                || !Objects.equals(source.getResourceSlotNo(), target.getResourceSlotNo())
                || !Objects.equals(source.getResourceGroupCode(), target.getResourceGroupCode())
                || !Objects.equals(source.getResourceGroupSlotNo(), target.getResourceGroupSlotNo())
                || !Objects.equals(source.getDeviceCode(), target.getDeviceCode())
                || !Objects.equals(source.getEmployeeCode(), target.getEmployeeCode());
        if (!changed) return;
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("parentOperationId", String.valueOf(source.getId()));
        diff.put("issueNo", source.getIssueNo());
        diff.put("processCode", source.getProcessCode());
        diff.put("oldStart", oldStart.toString());
        diff.put("newStart", target.getScheduledStartTime().toString());
        diff.put("oldEnd", oldEnd.toString());
        diff.put("newEnd", target.getScheduledEndTime().toString());
        diff.put("oldResource", source.getResourceType() + ":" + source.getResourceCode());
        diff.put("newResource", target.getResourceType() + ":" + target.getResourceCode());
        differences.add(diff);
    }

    private Integer nullableInt(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isIntegralNumber()) throw new CheckedException("许可资源快照中的" + name + "不是整数");
        return value.asInt();
    }

    private boolean sameDurationInput(JsonNode left, JsonNode right, String name) {
        JsonNode leftValue = left.path(name);
        JsonNode rightValue = right.path(name);
        if (leftValue.isMissingNode() || leftValue.isNull())
            return rightValue.isMissingNode() || rightValue.isNull();
        return leftValue.isNumber() && rightValue.isNumber()
                && leftValue.decimalValue().compareTo(rightValue.decimalValue()) == 0;
    }

    private PlnSchedulingCandidateProfileDTO candidateProfile(String snapshot) {
        try {
            return OBJECT_MAPPER.readValue(snapshot, PlnSchedulingCandidateProfileDTO.class);
        } catch (JsonProcessingException exception) {
            throw new CheckedException("候选目标权重快照损坏，无法重新比较");
        }
    }

    private void cloneSequenceProof(Long parentId, PlnSchedulingTaskEntity derived) {
        PlnSchedulingSequenceProof parentProof = sequenceProofs.selectOne(
                new LambdaQueryWrapper<PlnSchedulingSequenceProof>()
                        .eq(PlnSchedulingSequenceProof::getTaskId, parentId).last("LIMIT 1"));
        if (parentProof == null) throw new CheckedException("父候选缺少初始排序证据，不能安全派生");
        PlnSchedulingSequenceProof proof = copyForInsert(parentProof, PlnSchedulingSequenceProof.class);
        proof.setTaskId(derived.getId());
        if (sequenceProofs.insert(proof) != 1) throw new CheckedException("派生候选排序证据复制失败");
    }

    @Override
    public PlnSchedulingScenarioDTO selectScenarioCandidate(String scenarioNo,
                                                            PlnSchedulingScenarioSelectDTO command) {
        if (StrUtil.isBlank(scenarioNo) || command == null || StrUtil.isBlank(command.getRequestId())
                || command.getCandidateTaskId() == null || StrUtil.isBlank(command.getExpectedSourceFingerprint())
                || StrUtil.isBlank(command.getReason())) {
            throw new CheckedException("候选选择必须包含情景号、请求号、候选任务、来源指纹和选择原因");
        }
        String requestId = command.getRequestId().trim();
        if (requestId.length() > 64 || command.getReason().trim().length() > 500) {
            throw new CheckedException("候选选择请求号不能超过64位且原因不能超过500字");
        }
        plnSchedulingScenarioMapper.lock("APS_SCENARIO_SELECT:" + scenarioNo.trim());
        PlnSchedulingScenarioEntity scenario = plnSchedulingScenarioMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                        .eq(PlnSchedulingScenarioEntity::getScenarioNo, scenarioNo.trim()).last("FOR UPDATE"));
        if (scenario == null) throw new CheckedException("多候选排产情景不存在");
        String selectionFingerprint = sha256(requestId + "|" + command.getCandidateTaskId() + "|"
                + command.getExpectedSourceFingerprint() + "|" + command.getReason().trim());
        if ("SELECTED".equals(scenario.getScenarioStatus()) || "PUBLISHED".equals(scenario.getScenarioStatus())
                || "PARTIAL".equals(scenario.getScenarioStatus())) {
            if (Objects.equals(requestId, scenario.getSelectionRequestId())
                    && Objects.equals(selectionFingerprint, scenario.getSelectionFingerprint())) {
                return scenarioView(scenario);
            }
            throw new CheckedException("该情景已完成候选选择，不允许覆盖审计结论");
        }
        if (!"EVALUATED".equals(scenario.getScenarioStatus())) {
            throw new CheckedException("当前情景状态不允许选择候选：" + scenario.getScenarioStatus());
        }
        if (scenario.getExpiresTime() != null && scenario.getExpiresTime().isBefore(LocalDateTime.now())) {
            throw new CheckedException("多候选情景已过期，请重新求解");
        }
        if (!Objects.equals(scenario.getSourceFingerprint(), command.getExpectedSourceFingerprint())) {
            throw new CheckedException("候选来源指纹与情景不一致");
        }
        PlnSchedulingRuleSetEntity currentRule = requiredActiveRule();
        if (!Objects.equals(scenario.getRuleCode(), currentRule.getRuleCode())
                || !Objects.equals(scenario.getRuleVersion(), currentRule.getRuleVersion())) {
            throw new CheckedException("排产规则已变更，旧候选不可选择，请重新求解");
        }
        List<Long> sourceIds = parseLongList(scenario.getSourceIssueIdsSnapshot());
        List<PlnIssueEntity> current = plnIssueMapper.selectBatchIds(sourceIds);
        if (current.size() != sourceIds.size() || !Objects.equals(sourceFingerprint(current), scenario.getSourceFingerprint())) {
            throw new CheckedException("计划数量、交期、状态或版本已变化，旧候选不可选择");
        }
        List<PlnSchedulingTaskEntity> candidates = scenarioTasks(scenario.getId());
        PlnSchedulingTaskEntity selected = candidates.stream()
                .filter(item -> Objects.equals(item.getId(), command.getCandidateTaskId())).findFirst()
                .orElseThrow(() -> new CheckedException("候选任务不属于当前情景"));
        if (!Boolean.TRUE.equals(selected.getCandidateFeasible())) {
            throw new CheckedException("存在硬阻断的候选不能进入正式锁定");
        }
        candidates.forEach(item -> {
            item.setSelectedCandidate(Objects.equals(item.getId(), selected.getId()));
            if (!Objects.equals(item.getId(), selected.getId())) item.setTaskStatus("SUPERSEDED");
            plnSchedulingTaskMapper.updateById(item);
        });
        scenario.setSelectedTaskId(selected.getId());
        scenario.setSelectionRequestId(requestId);
        scenario.setSelectionFingerprint(selectionFingerprint);
        scenario.setSelectionReason(command.getReason().trim());
        scenario.setSelectedBy(currentUsername());
        scenario.setSelectedTime(LocalDateTime.now());
        scenario.setScenarioStatus("SELECTED");
        plnSchedulingScenarioMapper.updateById(scenario);
        return scenarioView(scenario);
    }

    @Override
    public Boolean replan(PlnSchedulingReplanDTO replanDTO) {
        if (replanDTO == null) {
            throw new CheckedException("重排目标不能为空");
        }
        List<Long> targetIds = normalizeTargetIds(replanDTO.getIssueId(), replanDTO.getTargetIds());
        List<PlnIssueEntity> issues = plnIssueMapper.selectBatchIds(targetIds);
        if (issues == null || issues.isEmpty()) {
            throw new CheckedException("计划不存在");
        }
        if (issues.size() != targetIds.size()) {
            throw new CheckedException("存在无效重排目标");
        }
        boolean hasClosed = issues.stream().anyMatch(issue -> "3".equals(issue.getStatus()) || "4".equals(issue.getStatus()));
        if (hasClosed) {
            throw new CheckedException("已完成或已关闭计划禁止重排");
        }

        PlnSchedulingRuleSetEntity activeRule = requiredActiveRule();
        LocalDate baseDate = LocalDate.now();
        LocalDate endDate = baseDate.plusDays(Math.max(0, issues.size() - 1L));
        List<PlnIssueEntity> scheduledPlans = filterAndSortByRule(issues, activeRule, baseDate, endDate,
                "BALANCED_GLOBAL", 2, "GREEDY_WINDOW");
        if (scheduledPlans.isEmpty()) {
            throw new CheckedException("规则过滤后无可重排计划");
        }

        PlnSchedulingRunDTO runDTO = new PlnSchedulingRunDTO();
        runDTO.setMode("official");
        runDTO.setTriggerType("MANUAL");
        runDTO.setCycleType("DAY");
        runDTO.setScheduleDate(baseDate);

        PlnSchedulingTaskEntity task = buildTask(runDTO, "WAIT_CONFIRM", activeRule, scheduledPlans.size(), baseDate);
        task.setRemark("重排任务，目标数=" + targetIds.size() + "，待确认锁定");
        int inserted = plnSchedulingTaskMapper.insert(task);
        if (inserted <= 0) {
            throw new CheckedException("重排任务写入失败");
        }

        for (PlnIssueEntity issue : scheduledPlans) {
            issue.setPlanType(activeRule.getRuleCode());
        }
        operationSchedulingService.scheduleTask(task.getId(), scheduledPlans);
        snapshotTaskDetails(task.getId(), scheduledPlans);
        schedulingBatchService.initializeSingleBatches(task.getId(), scheduledPlans);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public Boolean issueToModule(PlnIssueModuleDTO plnIssueModuleDTO) {
        if (plnIssueModuleDTO == null || plnIssueModuleDTO.getIds() == null
                || plnIssueModuleDTO.getIds().length == 0) {
            throw new CheckedException("请选择需要下达MES的生产计划");
        }
        List<PlnIssueEntity> plnIssueEntities = plnIssueMapper.selectBatchIds(Arrays.asList(plnIssueModuleDTO.getIds()));
        if (plnIssueEntities.size() != Arrays.stream(plnIssueModuleDTO.getIds()).distinct().count()) {
            throw new CheckedException("存在无效生产计划，禁止下达MES");
        }
        String factoryCode = plnIssueEntities.stream().map(PlnIssueEntity::getFactoryCode)
                .filter(StrUtil::isNotBlank).distinct().reduce((left, right) -> {
                    throw new CheckedException("不能跨工厂下达生产计划");
                }).orElseThrow(() -> new CheckedException("生产计划缺少工厂编码"));
        PlnApsFormalContractResolver.Resolution formalResolution =
                formalContractResolver.resolveIssues(plnIssueEntities, factoryCode);
        Set<Long> selectedIssueIds = plnIssueEntities.stream().map(PlnIssueEntity::getId).collect(Collectors.toSet());
        List<Long> lockTaskIds = plnIssueEntities.stream().map(PlnIssueEntity::getLockTaskId)
                .filter(Objects::nonNull).distinct().toList();
        List<PlnSchedulingBatchAllocationEntity> selectedBatchLinks = lockTaskIds.isEmpty() ? List.of()
                : plnSchedulingBatchAllocationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                        .in(PlnSchedulingBatchAllocationEntity::getTaskId, lockTaskIds)
                        .in(PlnSchedulingBatchAllocationEntity::getIssueId, selectedIssueIds));
        long linkedIssueCount = selectedBatchLinks.stream().map(PlnSchedulingBatchAllocationEntity::getIssueId)
                .distinct().count();
        if (!selectedBatchLinks.isEmpty()) {
            if (linkedIssueCount != selectedIssueIds.size()) {
                throw new CheckedException("所选计划同时包含批次化与历史非批次任务，请分开下达MES");
            }
            return issueApsBatches(plnIssueModuleDTO, plnIssueEntities, selectedBatchLinks, formalResolution);
        }

        List<PlnUpdateLogEntity> updateLogs = plnIssueEntities.stream()
                .filter(entity -> !"1".equals(entity.getStatus())) // 幂等：已下发的跳过
                .map(entity -> {
                    PlnUpdateLogEntity updateLog = createUpdateLog(entity, UpdateTypeEnum.STATUS_UPDATE.getCode(), entity.getStatus(), "1");
                    entity.setStatus("1");
                    return updateLog;
                }).toList();

        if (updateLogs.isEmpty()) {
            return Boolean.TRUE; // 全部已下发，直接返回
        }
        plnUpdateLogService.saveUpdateLog(updateLogs);

        // 只处理本次状态变更的计划（status 已被设为 "1"）
        List<PlnIssueEntity> toIssue = plnIssueEntities.stream()
                .filter(e -> "1".equals(e.getStatus()))
                .toList();

        for (PlnIssueEntity plnIssueEntity : toIssue) {
            plnIssueMapper.updateById(plnIssueEntity);
        }

        Map<String, List<PlanMatMrpEntity>> demandMrpMap = loadMrpsByIssue(toIssue);

        for (PlnIssueEntity plnIssueEntity : toIssue) {
            List<PlanMatMrpEntity> issueMrps = demandMrpMap.getOrDefault(plnIssueEntity.getIssueNo(), List.of());
            PlanMatMrpEntity productMrp = issueMrps.stream()
                    .filter(row -> Objects.equals(row.getMatCode(), plnIssueEntity.getProductCode()))
                    .findFirst().orElse(null);
            saveMesPlan(plnIssueEntity, plnIssueModuleDTO.getWorkshopName(), plnIssueModuleDTO.getWorkshopCode(),
                    productMrp, issueMrps, formalResolution);
        }

        return Boolean.TRUE;
    }

    private Boolean schedulingTaskOnly(PlnSchedulingRunDTO runDTO) {
        LocalDate baseDate = resolveScheduleDate(runDTO);
        LocalDate startDate = getCycleStartDate(runDTO.getCycleType(), baseDate);
        LocalDate endDate = getCycleEndDate(runDTO.getCycleType(), baseDate, runDTO.getPlanNum());
        List<PlnIssueEntity> plans = listUnissuedPlansInRange(startDate, endDate, normalizeRunTargetIds(runDTO.getTargetIds()));
        int planCount = plans.size();
        if (planCount <= 0) {
            throw new CheckedException("当前周期无可排产计划");
        }
        PlnSchedulingRuleSetEntity activeRule = requiredActiveRule();
        LocalDate scheduleStart = resolveScheduleStartDate(runDTO.getCycleType(), baseDate);
        PlnSchedulingTaskEntity task = buildTask(runDTO, "PENDING", activeRule, planCount, scheduleStart);
        task.setRemark("模拟排产范围: " + startDate + " ~ " + endDate + "，计划数=" + planCount + (runAsTnRange(runDTO.getCycleType()) ? "，T+N=" + (runDTO.getPlanNum() == null ? defaultPlanNum() : Math.max(1, runDTO.getPlanNum())) : ""));
        int inserted = plnSchedulingTaskMapper.insert(task);
        if (inserted > 0) {
            List<PlnIssueEntity> scheduledPlans = filterAndSortByRule(plans, activeRule, scheduleStart, endDate,
                    "BALANCED_GLOBAL", 2, runDTO.getSequenceSolverCode());
            if (scheduledPlans.isEmpty()) throw new CheckedException("规则过滤后无可模拟计划，不生成已完成结果");
            task.setPlanCount(scheduledPlans.size());
            plnSchedulingTaskMapper.updateById(task);
            operationSchedulingService.scheduleTask(task.getId(), scheduledPlans);
            snapshotTaskDetails(task.getId(), scheduledPlans);
            schedulingBatchService.initializeSingleBatches(task.getId(), scheduledPlans);
            task.setTaskStatus("SIMULATED");
            if (plnSchedulingTaskMapper.updateById(task) != 1) throw new CheckedException("模拟排产结果状态保存失败");
        }
        return inserted > 0;
    }

    private Boolean writeOfficialIssue(PlnSchedulingRunDTO runDTO) {
        LocalDate baseDate = resolveScheduleDate(runDTO);
        LocalDate startDate = getCycleStartDate(runDTO.getCycleType(), baseDate);
        LocalDate endDate = getCycleEndDate(runDTO.getCycleType(), baseDate, runDTO.getPlanNum());
        List<PlnIssueEntity> plans = listUnissuedPlansInRange(startDate, endDate, normalizeRunTargetIds(runDTO.getTargetIds()));
        if (plans == null || plans.isEmpty()) {
            throw new CheckedException("当前周期无可排产计划");
        }
        PlnSchedulingRuleSetEntity activeRule = requiredActiveRule();
        LocalDate scheduleStart = resolveScheduleStartDate(runDTO.getCycleType(), baseDate);
        List<PlnIssueEntity> scheduledPlans = filterAndSortByRule(plans, activeRule, scheduleStart, endDate,
                "BALANCED_GLOBAL", 2, runDTO.getSequenceSolverCode());
        if (scheduledPlans == null || scheduledPlans.isEmpty()) {
            throw new CheckedException("规则过滤后无可排产计划");
        }

        PlnSchedulingTaskEntity task = buildTask(runDTO, "WAIT_CONFIRM", activeRule, scheduledPlans.size(), scheduleStart);
        task.setRemark("正式排产范围: " + startDate + " ~ " + endDate + "，计划数=" + scheduledPlans.size() + (runAsTnRange(runDTO.getCycleType()) ? "，T+N=" + (runDTO.getPlanNum() == null ? defaultPlanNum() : Math.max(1, runDTO.getPlanNum())) : "") + "，待确认锁定");
        int taskInserted = plnSchedulingTaskMapper.insert(task);
        if (taskInserted <= 0) {
            throw new CheckedException("正式排产任务写入失败");
        }

        for (PlnIssueEntity issue : scheduledPlans) {
            issue.setPlanType(activeRule != null ? activeRule.getRuleCode() : issue.getPlanType());
        }

        operationSchedulingService.scheduleTask(task.getId(), scheduledPlans);
        snapshotTaskDetails(task.getId(), scheduledPlans);
        schedulingBatchService.initializeSingleBatches(task.getId(), scheduledPlans);
        return true;
    }

    @Override
    public Boolean confirmOfficialTaskLock(Long taskId) {
        if (taskId == null) {
            throw new CheckedException("任务ID不能为空");
        }
        PlnSchedulingTaskEntity initialTask = plnSchedulingTaskMapper.selectById(taskId);
        if (initialTask == null) {
            throw new CheckedException("任务不存在");
        }
        // 与整组取消保持相同顺序：先情景、再任务，禁止取消与正式锁定各自基于旧状态成功。
        if (initialTask.getScenarioId() != null) {
            var parent = plnSchedulingScenarioMapper.selectOne(new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                    .eq(PlnSchedulingScenarioEntity::getId, initialTask.getScenarioId()).last("FOR UPDATE"));
            if (parent == null) throw new CheckedException("排产情景不存在");
        }
        if (plnSchedulingTaskMapper.acquireTransactionLock(taskId) == null)
            throw new CheckedException("排产任务不存在");
        PlnSchedulingTaskEntity task = plnSchedulingTaskMapper.selectById(taskId);
        if (Boolean.TRUE.equals(task.getResultArchived())) throw new CheckedException("已归档结果不能确认锁定");
        if (task.getScenarioId() != null && !Boolean.TRUE.equals(task.getSelectedCandidate())) {
            throw new CheckedException("多候选情景必须先选择且只能锁定被选中的候选方案");
        }
        if (task.getScenarioId() != null) {
            PlnSchedulingScenarioEntity scenario = plnSchedulingScenarioMapper.selectById(task.getScenarioId());
            if (scenario == null || !Objects.equals(scenario.getSelectedTaskId(), taskId)
                    || !"SELECTED".equals(scenario.getScenarioStatus())) {
                throw new CheckedException("多候选情景选择状态不一致，禁止正式锁定");
            }
        }
        if (!"official".equalsIgnoreCase(task.getMode())) {
            throw new CheckedException("仅正式排产任务支持确认锁定");
        }
        if (!"WAIT_CONFIRM".equalsIgnoreCase(task.getTaskStatus())) {
            throw new CheckedException("当前任务状态不允许确认锁定");
        }

        List<com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity> details = plnSchedulingTaskDetailMapper.selectList(
                new LambdaQueryWrapper<com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity>()
                        .eq(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getTaskId, taskId)
                        .orderByAsc(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getDemandTime)
                        .orderByAsc(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getIssueNo)
        );
        if (details == null || details.isEmpty()) {
            throw new CheckedException("任务明细为空，无法确认锁定");
        }

        refreshMaterialConstraints(details, LocalDate.now());
        schedulingBatchService.validateBeforeLock(taskId);
        operationSchedulingService.validateBeforeLock(taskId, details);

        List<PlnSchedulingConflictEntity> engineConflicts = conflictEngine.detect(taskId, details);
        Set<Long> blockedIssueIds = new HashSet<>();
        // 幂等去重：预加载当前任务已存在的冲突记录，防止重入时重复插入
        Set<String> existingConflictKeys = plnSchedulingConflictMapper.selectList(
                        new LambdaQueryWrapper<PlnSchedulingConflictEntity>()
                                .eq(PlnSchedulingConflictEntity::getTaskId, taskId)
                                .select(PlnSchedulingConflictEntity::getIssueId,
                                        PlnSchedulingConflictEntity::getConflictType))
                .stream()
                .map(c -> taskId + "#" + c.getIssueId() + "#" + c.getConflictType())
                .collect(Collectors.toCollection(HashSet::new));
        int successCount = 0;
        int conflictCount = 0;
        int alreadyLockedCount = 0;
        if (engineConflicts != null && !engineConflicts.isEmpty()) {
            for (PlnSchedulingConflictEntity conflict : engineConflicts) {
                String dedupKey = taskId + "#" + conflict.getIssueId() + "#" + conflict.getConflictType();
                if (Boolean.TRUE.equals(conflict.getHardConstraint()) && conflict.getIssueId() != null) {
                    blockedIssueIds.add(conflict.getIssueId());
                }
                if (!existingConflictKeys.add(dedupKey)) {
                    continue;
                }
                plnSchedulingConflictMapper.insert(conflict);
                conflictCount++;
            }
        }
        // 合批是一个不可分割的执行批次；任一来源计划出现物料、质量、工序等硬阻断时，
        // 同批全部来源都必须停止锁定，禁止只锁定合批中的“另一半”。
        if (!blockedIssueIds.isEmpty()) {
            PlnSchedulingBatchLedgerDTO batchLedger = schedulingBatchService.ledger(taskId);
            Set<Long> expanded = batchLedger.getBatches().stream()
                    .map(PlnSchedulingBatchLedgerDTO.Batch::getAllocations)
                    .filter(Objects::nonNull)
                    .filter(allocations -> allocations.stream().map(PlnSchedulingBatchAllocationEntity::getIssueId)
                            .anyMatch(blockedIssueIds::contains))
                    .flatMap(Collection::stream).map(PlnSchedulingBatchAllocationEntity::getIssueId)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            blockedIssueIds.addAll(expanded);
        }

        int dayCapacityThreshold = resolveDayCapacityThreshold(task);
        for (com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity detail : details) {
            if (detail.getIssueId() != null && blockedIssueIds.contains(detail.getIssueId())) {
                continue;
            }
            PlnIssueEntity issue = plnIssueMapper.selectById(detail.getIssueId());
            if (issue == null) {
                if (detail.getIssueId() != null) {
                    blockedIssueIds.add(detail.getIssueId());
                }
                String dedupKey = taskId + "#" + detail.getIssueId() + "#ISSUE_NOT_FOUND";
                if (existingConflictKeys.add(dedupKey)) {
                    PlnSchedulingConflictEntity conflict = new PlnSchedulingConflictEntity();
                    conflict.setTaskId(taskId);
                    conflict.setIssueId(detail.getIssueId());
                    conflict.setIssueNo(detail.getIssueNo());
                    conflict.setProductCode(detail.getProductCode());
                    conflict.setConflictType("ISSUE_NOT_FOUND");
                    conflict.setConflictLevel("HIGH");
                    conflict.setHardConstraint(Boolean.TRUE);
                    conflict.setConflictContent("计划" + detail.getIssueNo() + "不存在或已删除，无法锁定");
                    conflict.setResolved(Boolean.FALSE);
                    plnSchedulingConflictMapper.insert(conflict);
                    conflictCount++;
                }
                continue;
            }
            LocalDate scheduledDate = detail.getScheduledDate();
            if (scheduledDate == null) {
                if (detail.getIssueId() != null) {
                    blockedIssueIds.add(detail.getIssueId());
                }
                String dedupKey = taskId + "#" + detail.getIssueId() + "#SCHEDULE_DATE_MISSING";
                if (existingConflictKeys.add(dedupKey)) {
                    PlnSchedulingConflictEntity conflict = new PlnSchedulingConflictEntity();
                    conflict.setTaskId(taskId);
                    conflict.setConflictType("SCHEDULE_DATE_MISSING");
                    conflict.setConflictLevel("HIGH");
                    conflict.setHardConstraint(Boolean.TRUE);
                    conflict.setIssueId(detail.getIssueId());
                    conflict.setIssueNo(detail.getIssueNo());
                    conflict.setProductCode(detail.getProductCode());
                    conflict.setConflictContent("计划" + detail.getIssueNo() + "缺少分配日期，无法锁定");
                    conflict.setResolved(Boolean.FALSE);
                    plnSchedulingConflictMapper.insert(conflict);
                    conflictCount++;
                }
                continue;
            }
            if (Boolean.TRUE.equals(issue.getLockStatus()) && issue.getLockTaskId() != null && !taskId.equals(issue.getLockTaskId())) {
                // 计划已被其他任务锁定，跳过但单独计数，不算冲突也不算成功
                alreadyLockedCount++;
                continue;
            }
            if (detail.getDemandTime() != null && scheduledDate.isAfter(detail.getDemandTime())) {
                if (detail.getIssueId() != null) {
                    blockedIssueIds.add(detail.getIssueId());
                }
                String dedupKey = taskId + "#" + detail.getIssueId() + "#SCHEDULE_AFTER_DEMAND_DATE";
                if (existingConflictKeys.add(dedupKey)) {
                    PlnSchedulingConflictEntity conflict = new PlnSchedulingConflictEntity();
                    conflict.setTaskId(taskId);
                    conflict.setConflictType("SCHEDULE_AFTER_DEMAND_DATE");
                    conflict.setConflictLevel("HIGH");
                    conflict.setHardConstraint(Boolean.TRUE);
                    conflict.setIssueId(detail.getIssueId());
                    conflict.setIssueNo(detail.getIssueNo());
                    conflict.setProductCode(detail.getProductCode());
                    conflict.setConflictContent("计划" + detail.getIssueNo() + "分配日期" + scheduledDate + "晚于需求日期" + detail.getDemandTime() + "，禁止锁定");
                    conflict.setResolved(Boolean.FALSE);
                    plnSchedulingConflictMapper.insert(conflict);
                    conflictCount++;
                }
                continue;
            }
            Long sameDayCount = plnIssueMapper.selectCount(new LambdaQueryWrapper<PlnIssueEntity>()
                    .eq(PlnIssueEntity::getLockStatus, true)
                    .eq(PlnIssueEntity::getProductCode, detail.getProductCode())
                    .eq(PlnIssueEntity::getScheduledDate, scheduledDate)
                    .ne(PlnIssueEntity::getId, detail.getIssueId()));
            if (sameDayCount != null && sameDayCount >= dayCapacityThreshold) {
                String dedupKey = taskId + "#" + detail.getIssueId() + "#PRODUCT_DAY_CAPACITY_EXCEEDED";
                if (existingConflictKeys.add(dedupKey)) {
                    PlnSchedulingConflictEntity conflict = new PlnSchedulingConflictEntity();
                    conflict.setTaskId(taskId);
                    conflict.setConflictType("PRODUCT_DAY_CAPACITY_EXCEEDED");
                    conflict.setConflictLevel("MEDIUM");
                    conflict.setHardConstraint(Boolean.FALSE);
                    conflict.setIssueId(detail.getIssueId());
                    conflict.setIssueNo(detail.getIssueNo());
                    conflict.setProductCode(detail.getProductCode());
                    conflict.setConflictContent("产品" + detail.getProductCode() + "在" + scheduledDate + "当天排产数量超过阈值" + dayCapacityThreshold);
                    conflict.setResolved(Boolean.FALSE);
                    plnSchedulingConflictMapper.insert(conflict);
                    conflictCount++;
                }
            }

            issue.setType("issue");
            issue.setPlanType(task.getRuleCode() != null ? task.getRuleCode() : issue.getPlanType());
            issue.setScheduledDate(scheduledDate);
            issue.setIssueTime(scheduledDate.atStartOfDay());
            issue.setLockStatus(Boolean.TRUE);
            issue.setLockTaskId(taskId);
            issue.setLockTime(LocalDateTime.now());
            plnIssueMapper.updateById(issue);

            plnSchedulingTaskDetailMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity>()
                    .eq(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getTaskId, taskId)
                    .eq(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getIssueId, detail.getIssueId())
                    .set(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getLockStatus, true)
                    .set(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getLockTaskId, taskId)
                    .set(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getLockTime, issue.getLockTime())
                    .set(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getType, "issue")
                    .set(com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity::getPlanType, issue.getPlanType()));

            operationSchedulingService.lockIssueOperations(taskId, detail.getIssueId(), issue.getLockTime());
            schedulingBatchService.lockIssueBatches(taskId, detail.getIssueId(), issue.getLockTime());

            // 若该计划已下发 MES（status=1），同步更新 MES 工单计划时间
            if ("1".equals(issue.getStatus())) {
                syncMesPlanDate(issue);
            }
            successCount++;
        }

        // successCount=0 且全部被其他任务锁定：该任务已无效，直接抛异常
        if (successCount == 0 && conflictCount == 0 && alreadyLockedCount > 0) {
            task.setTaskStatus("PARTIAL");
            task.setRemark((task.getRemark() == null ? "" : task.getRemark())
                    + "，确认锁定=0，已被其他任务锁定=" + alreadyLockedCount + "，该任务已失效");
            plnSchedulingTaskMapper.updateById(task);
            throw new CheckedException("该任务所有计划（共" + alreadyLockedCount + "条）已被其他排产任务锁定，无需重复确认");
        }
        // planCount 保持任务创建时的原始值，不覆盖，仅追加 remark
        task.setTaskStatus(conflictCount > 0 || !blockedIssueIds.isEmpty() ? "PARTIAL" : "SUCCESS");
        task.setRemark((task.getRemark() == null ? "" : task.getRemark())
                + "，确认锁定=" + successCount + "，新增冲突=" + conflictCount
                + "，硬阻断计划=" + blockedIssueIds.size()
                + (alreadyLockedCount > 0 ? "，已被其他任务锁定(跳过)=" + alreadyLockedCount : ""));
        plnSchedulingTaskMapper.updateById(task);
        if (task.getScenarioId() != null) {
            PlnSchedulingScenarioEntity scenario = plnSchedulingScenarioMapper.selectById(task.getScenarioId());
            if (scenario != null && Objects.equals(scenario.getSelectedTaskId(), taskId)) {
                scenario.setScenarioStatus("SUCCESS".equals(task.getTaskStatus()) ? "PUBLISHED" : "PARTIAL");
                plnSchedulingScenarioMapper.updateById(scenario);
            }
        }
        return true;
    }

    @Override
    public PlnSchedulingBatchLedgerDTO configureBatches(PlnSchedulingBatchConfigureDTO configureDTO) {
        if (configureDTO == null || configureDTO.getTaskId() == null) {
            throw new CheckedException("排产批次调整参数不完整");
        }
        boolean changed = schedulingBatchService.configure(configureDTO);
        PlnSchedulingBatchLedgerDTO ledger = schedulingBatchService.ledger(configureDTO.getTaskId());
        if (changed) {
            operationSchedulingService.scheduleTaskBatches(configureDTO.getTaskId(), ledger);
            ledger = schedulingBatchService.ledger(configureDTO.getTaskId());
        }
        return ledger;
    }

    /**
     * 重排确认锁定后，同步更新 MES 工单的计划开工/完工时间。
     * 失败只记录警告日志，不影响排产主流程。
     */
    private void syncMesPlanDate(PlnIssueEntity issue) {
        try {
            List<PlnSchedulingOperationEntity> operations = issue.getLockTaskId() == null ? List.of()
                    : operationSchedulingService.listTaskOperations(issue.getLockTaskId(), issue.getId(), null);
            LocalDateTime operationStart = operations.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                    .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
            LocalDateTime operationEnd = operations.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                    .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
            LocalDate scheduledDate = nextWorkDay(issue.getScheduledDate() != null
                    ? issue.getScheduledDate() : LocalDate.now());
            List<MdmClassesEntity> shifts = queryActiveShifts();
            LocalTime firstShiftStart = shifts.isEmpty()
                    ? LocalTime.of(PLAN_START_HOUR, 0) : shifts.get(0).getStartTime();
            LocalDateTime planStart = operationStart != null ? operationStart : scheduledDate.atTime(firstShiftStart);
            LocalDateTime planEnd = operationEnd != null ? operationEnd : resolvePlanEndDate(
                    issue.getProductCode(), issue.getNum(), planStart, issue.getDemandTime());

            MesTaskEntity update = new MesTaskEntity();
            update.setPlanNo(issue.getIssueNo());
            update.setPlanStartDate(planStart);
            update.setPlanEndDate(planEnd);
            R<Void> resp = remoteTaskService.update(update);
            if (resp == null || !ObjUtil.equals(CommonConstants.SUCCESS, resp.getCode())) {
                log.warn("[重排同步] MES 工单更新失败，planNo={}，忽略继续。", issue.getIssueNo());
            }
        } catch (Exception e) {
            log.warn("[重排同步] MES 工单更新异常，planNo={}，原因: {}", issue.getIssueNo(), e.getMessage());
        }
    }

    private PlnUpdateLogEntity createUpdateLog(PlnIssueEntity issue, String updateType, String oldValue, String newValue) {
        PlnUpdateLogEntity updateLog = new PlnUpdateLogEntity();
        updateLog.setCustomerCode(issue.getCustomerCode());
        updateLog.setCustomerName(issue.getCustomerName());
        updateLog.setProductCode(issue.getProductCode());
        updateLog.setProductName(issue.getProductName());
        updateLog.setSpecificationModel(issue.getSpecificationModel());
        updateLog.setMaterialAttribute(issue.getMaterialAttribute());
        updateLog.setOperateTime(issue.getIssueTime());
        updateLog.setModule(ModuleEnum.MES.getCode());
        updateLog.setPlanType(issue.getPlanType());
        updateLog.setUpdateType(updateType);
        updateLog.setOldValue(oldValue);
        updateLog.setNewValue(newValue);
        return updateLog;
    }

    private Boolean issueApsBatches(PlnIssueModuleDTO request, List<PlnIssueEntity> selectedIssues,
                                    List<PlnSchedulingBatchAllocationEntity> selectedLinks,
                                    PlnApsFormalContractResolver.Resolution formalResolution) {
        Set<Long> selectedIssueIds = selectedIssues.stream().map(PlnIssueEntity::getId).collect(Collectors.toSet());
        Set<Long> batchIds = selectedLinks.stream().map(PlnSchedulingBatchAllocationEntity::getBatchId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        List<PlnSchedulingBatchEntity> batches = plnSchedulingBatchMapper.selectBatchIds(batchIds).stream()
                .sorted(Comparator.comparing(PlnSchedulingBatchEntity::getTaskId)
                        .thenComparing(PlnSchedulingBatchEntity::getBatchSeq)).toList();
        if (batches.size() != batchIds.size() || batches.stream().anyMatch(batch ->
                !Boolean.TRUE.equals(batch.getLockStatus()) || !"LOCKED".equals(batch.getBatchStatus()))) {
            throw new CheckedException("APS批次不存在或尚未完成正式锁定，禁止下达MES");
        }
        List<PlnSchedulingBatchAllocationEntity> allocations = plnSchedulingBatchAllocationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingBatchAllocationEntity>()
                        .in(PlnSchedulingBatchAllocationEntity::getBatchId, batchIds)
                        .orderByAsc(PlnSchedulingBatchAllocationEntity::getBatchNo)
                        .orderByAsc(PlnSchedulingBatchAllocationEntity::getAllocationSeq));
        Set<Long> allSourceIds = allocations.stream().map(PlnSchedulingBatchAllocationEntity::getIssueId)
                .collect(Collectors.toSet());
        if (!selectedIssueIds.equals(allSourceIds)) {
            List<String> missing = allocations.stream().filter(item -> !selectedIssueIds.contains(item.getIssueId()))
                    .map(PlnSchedulingBatchAllocationEntity::getIssueNo).distinct().toList();
            throw new CheckedException("合批不可部分下达，请同时选择来源计划：" + String.join("、", missing));
        }
        Map<Long, PlnIssueEntity> issueById = selectedIssues.stream()
                .collect(Collectors.toMap(PlnIssueEntity::getId, Function.identity()));
        Map<Long, PlnSchedulingBatchEntity> batchById = batches.stream()
                .collect(Collectors.toMap(PlnSchedulingBatchEntity::getId, Function.identity()));
        for (PlnSchedulingBatchAllocationEntity allocation : allocations) {
            PlnIssueEntity issue = issueById.get(allocation.getIssueId());
            PlnSchedulingBatchEntity batch = batchById.get(allocation.getBatchId());
            if (issue == null || batch == null || !Boolean.TRUE.equals(issue.getLockStatus())
                    || !Objects.equals(issue.getLockTaskId(), batch.getTaskId())
                    || !Set.of("0", "1").contains(issue.getStatus())) {
                throw new CheckedException("来源计划[" + allocation.getIssueNo()
                        + "]未锁定、已进入生产或不属于当前APS批次");
            }
        }

        Map<String, List<PlanMatMrpEntity>> mrpsByIssueNo = loadMrpsByIssue(selectedIssues);
        Map<Long, List<WmsMaterialReservationLinkDTO>> reservationLinksByBatch =
                allocateReservationLinksByBatch(selectedIssues, allocations, mrpsByIssueNo, batchById);
        Map<Long, List<PlnSchedulingBatchAllocationEntity>> allocationsByBatch = allocations.stream()
                .collect(Collectors.groupingBy(PlnSchedulingBatchAllocationEntity::getBatchId));
        for (PlnSchedulingBatchEntity batch : batches) {
            saveMesBatch(batch, allocationsByBatch.getOrDefault(batch.getId(), List.of()), issueById,
                    reservationLinksByBatch.getOrDefault(batch.getId(), List.of()), mrpsByIssueNo,
                    request.getWorkshopName(), request.getWorkshopCode(), formalResolution);
        }

        List<PlnUpdateLogEntity> logs = new ArrayList<>();
        for (PlnIssueEntity issue : selectedIssues) {
            if ("1".equals(issue.getStatus())) continue;
            logs.add(createUpdateLog(issue, UpdateTypeEnum.STATUS_UPDATE.getCode(), issue.getStatus(), "1"));
            issue.setStatus("1");
            if (plnIssueMapper.updateById(issue) != 1) {
                throw new CheckedException("计划[" + issue.getIssueNo() + "]下达状态更新失败");
            }
        }
        if (!logs.isEmpty()) plnUpdateLogService.saveUpdateLog(logs);
        return Boolean.TRUE;
    }

    /**
     * 生产计划号是执行单据身份，产品需求号是业务来源；MPS跨期拆量后两者不再相同。
     * 先按来源需求批量取数，再用MPS桶或独立计划来源键隔离每一张生产计划的MRP。
     */
    private Map<String, List<PlanMatMrpEntity>> loadMrpsByIssue(List<PlnIssueEntity> issues) {
        if (CollUtil.isEmpty(issues)) return Map.of();
        Set<String> demandNos = issues.stream()
                .map(issue -> StrUtil.blankToDefault(issue.getSourceDemandNo(), issue.getIssueNo()))
                .filter(StrUtil::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new));
        if (demandNos.isEmpty()) return Map.of();
        List<PlanMatMrpEntity> candidates = planMatMrpService.list(
                new LambdaQueryWrapper<PlanMatMrpEntity>()
                        .in(PlanMatMrpEntity::getPlanDemandNo, demandNos)
                        .orderByAsc(PlanMatMrpEntity::getId));
        Map<String, List<PlanMatMrpEntity>> result = new LinkedHashMap<>();
        for (PlnIssueEntity issue : issues) {
            String demandNo = StrUtil.blankToDefault(issue.getSourceDemandNo(), issue.getIssueNo());
            List<PlanMatMrpEntity> matched = candidates.stream()
                    .filter(row -> Objects.equals(row.getPlanDemandNo(), demandNo))
                    .filter(row -> matchesPlanningSource(issue, row)).toList();
            result.put(issue.getIssueNo(), matched);
        }
        return result;
    }

    private boolean matchesPlanningSource(PlnIssueEntity issue, PlanMatMrpEntity mrp) {
        if (issue.getMpsLineId() != null) return Objects.equals(issue.getMpsLineId(), mrp.getMpsLineId());
        if (StrUtil.isNotBlank(issue.getPlanningSourceKey())) {
            return Objects.equals(issue.getPlanningSourceKey(), mrp.getPlanningSourceKey());
        }
        return mrp.getMpsLineId() == null;
    }

    private Map<Long, List<WmsMaterialReservationLinkDTO>> allocateReservationLinksByBatch(
            List<PlnIssueEntity> issues, List<PlnSchedulingBatchAllocationEntity> allocations,
            Map<String, List<PlanMatMrpEntity>> mrpsByIssueNo, Map<Long, PlnSchedulingBatchEntity> batchById) {
        Map<Long, List<WmsMaterialReservationLinkDTO>> result = new LinkedHashMap<>();
        Map<Long, List<PlnSchedulingBatchAllocationEntity>> byIssue = allocations.stream()
                .collect(Collectors.groupingBy(PlnSchedulingBatchAllocationEntity::getIssueId));
        for (PlnIssueEntity issue : issues) {
            List<PlnSchedulingBatchAllocationEntity> issueAllocations = byIssue
                    .getOrDefault(issue.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(item -> batchById.get(item.getBatchId()).getBatchSeq())).toList();
            BigDecimal allocatedTotal = issueAllocations.stream()
                    .map(PlnSchedulingBatchAllocationEntity::getAllocatedQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (issue.getNum() == null || issue.getNum().signum() <= 0
                    || allocatedTotal.compareTo(issue.getNum()) != 0) {
                throw new CheckedException("计划[" + issue.getIssueNo() + "]拆批分配量不守恒，禁止拆分物料预留");
            }
            for (WmsMaterialReservationLinkDTO link : reservationLinksForIssue(
                    mrpsByIssueNo.getOrDefault(issue.getIssueNo(), List.of()))) {
                BigDecimal remaining = link.getReservedActualNum();
                if (remaining == null || remaining.signum() <= 0) continue;
                BigDecimal original = remaining;
                for (int index = 0; index < issueAllocations.size(); index++) {
                    PlnSchedulingBatchAllocationEntity allocation = issueAllocations.get(index);
                    BigDecimal portion = index == issueAllocations.size() - 1 ? remaining
                            : original.multiply(allocation.getAllocatedQuantity())
                            .divide(issue.getNum(), 8, RoundingMode.HALF_UP).min(remaining);
                    remaining = remaining.subtract(portion);
                    if (portion.signum() > 0) {
                        result.computeIfAbsent(allocation.getBatchId(), key -> new ArrayList<>())
                                .add(copyReservationLink(link, portion));
                    }
                }
                if (remaining.signum() != 0) {
                    throw new CheckedException("计划[" + issue.getIssueNo() + "]物料预留拆分出现尾差");
                }
            }
        }
        return result;
    }

    private void saveMesBatch(PlnSchedulingBatchEntity batch,
                              List<PlnSchedulingBatchAllocationEntity> allocations,
                              Map<Long, PlnIssueEntity> issueById,
                              List<WmsMaterialReservationLinkDTO> reservationLinks,
                              Map<String, List<PlanMatMrpEntity>> mrpsByIssueNo,
                              String workshopName, String workshopCode,
                              PlnApsFormalContractResolver.Resolution formalResolution) {
        if (allocations.isEmpty()) throw new CheckedException("APS批次[" + batch.getBatchNo() + "]缺少来源分配");
        PlnIssueEntity primary = issueById.get(batch.getPrimaryIssueId());
        if (primary == null) throw new CheckedException("APS批次[" + batch.getBatchNo() + "]主计划不存在");
        List<PlnSchedulingOperationEntity> operations = operationSchedulingService
                .listBatchOperations(batch.getTaskId(), batch.getId());
        if (operations.isEmpty() || operations.stream().anyMatch(operation ->
                !Boolean.TRUE.equals(operation.getLockStatus()) || Boolean.TRUE.equals(operation.getHardBlocked())
                        || operation.getPlannedQuantity() == null
                        || operation.getPlannedQuantity().compareTo(batch.getPlannedQuantity()) != 0
                        || !Objects.equals(operation.getBatchAllocationSnapshot(), batch.getAllocationSnapshot()))) {
            throw new CheckedException("APS批次[" + batch.getBatchNo() + "]工序未锁定或数量/来源快照不一致");
        }
        MesTaskReceiveDTO receive = new MesTaskReceiveDTO();
        receive.setPlanNo(primary.getIssueNo());
        receive.setMatCode(primary.getProductCode());
        receive.setMatName(primary.getProductName());
        receive.setMatSpec(primary.getSpecificationModel());
        receive.setNoticeNum(batch.getPlannedQuantity());
        receive.setWorkshopName(workshopName);
        receive.setWorkshopCode(workshopCode);
        receive.setFactoryCode(batch.getFactoryCode());
        applyFormalContract(receive, formalResolution,
                allocations.stream().map(PlnSchedulingBatchAllocationEntity::getIssueId).toList(), primary);
        receive.setResourceType("APS_BATCH");
        receive.setResourceId(batch.getId());
        receive.setApsTaskId(batch.getTaskId());
        receive.setApsBatchId(batch.getId());
        receive.setApsBatchNo(batch.getBatchNo());
        receive.setApsBatchType(batch.getBatchType());
        receive.setApsBatchFingerprint(batch.getSourceFingerprint());
        receive.setEngineeringReleaseId(batch.getEngineeringReleaseId());
        receive.setEngineeringContentHash(batch.getEngineeringContentHash());
        receive.setEngineeringVersionNo(batch.getEngineeringVersionNo());
        receive.setApsBatchAllocations(allocations.stream().map(allocation -> new MesApsBatchAllocationDTO()
                .setAllocationSeq(allocation.getAllocationSeq()).setIssueId(allocation.getIssueId())
                .setIssueNo(allocation.getIssueNo())
                .setIssueQuantitySnapshot(allocation.getIssueQuantitySnapshot())
                .setAllocatedQuantity(allocation.getAllocatedQuantity())).toList());
        receive.setMaterialReservationSnapshot(JSONUtil.toJsonStr(reservationLinks));
        receive.setApsOperationSnapshot(JSONUtil.toJsonStr(operations));
        receive.setPlanStartDate(toDate(batch.getScheduledStartTime()));
        receive.setPlanEndDate(toDate(batch.getScheduledEndTime()));
        receive.setProcessRouteCode(batch.getRouteCode());
        List<PlanMatMrpEntity> primaryMrps = mrpsByIssueNo.getOrDefault(primary.getIssueNo(), List.of());
        receive.setType(primaryMrps.stream().anyMatch(item -> Objects.equals(item.getMatCode(), primary.getProductCode())
                && !ObjUtil.equal(item.getSubitemBomId(), 0L)) ? "PART" : "PRODUCT");
        sealMesIssueRequest(receive, "PLAN_APS_BATCH:" + batch.getId());
        R<MesTaskIssueReceiptDTO> response = remoteTaskService.add(receive);
        if (response == null || !ObjUtil.equals(CommonConstants.SUCCESS, response.getCode())
                || response.getData() == null
                || !Objects.equals(receive.getSourceRequestId(), response.getData().getRequestId())
                || !Objects.equals(receive.getSourceRequestFingerprint(), response.getData().getRequestFingerprint())
                || response.getData().getTaskId() == null || StrUtil.isBlank(response.getData().getTaskCode())) {
            throw new CheckedException("APS批次[" + batch.getBatchNo() + "]推送MES失败");
        }
    }

    private List<WmsMaterialReservationLinkDTO> reservationLinksForIssue(List<PlanMatMrpEntity> demandMrpList) {
        List<WmsMaterialReservationLinkDTO> reservationLinks = new ArrayList<>();
        for (PlanMatMrpEntity mrp : demandMrpList) {
            if (StrUtil.isNotBlank(mrp.getMainMaterialReservationNo())
                    && "ACTIVE".equals(mrp.getMainMaterialReservationStatus())
                    && Optional.ofNullable(mrp.getStockAllocatedNum()).orElse(BigDecimal.ZERO).signum() > 0) {
                reservationLinks.add(new WmsMaterialReservationLinkDTO()
                        .setReservationNo(mrp.getMainMaterialReservationNo())
                        .setWarehouseCode(mrp.getDemandWarehouseCode()).setRequirementMatCode(mrp.getMatCode())
                        .setActualMatCode(mrp.getMatCode()).setSubstituteRatio(BigDecimal.ONE)
                        .setReservedActualNum(mrp.getStockAllocatedNum()).setComplianceMode("STANDARD")
                        .setInspectionLevel("NORMAL"));
            }
            if (mrp.getMaterialReservationNo() == null || !"ACTIVE".equals(mrp.getMaterialReservationStatus())
                    || mrp.getSubstituteAllocation() == null) continue;
            try {
                List<com.kitex.mom.plan.dto.PlanMatSubstituteAllocationDTO> substitutes = JSONUtil.toList(
                        mrp.getSubstituteAllocation(), com.kitex.mom.plan.dto.PlanMatSubstituteAllocationDTO.class);
                for (com.kitex.mom.plan.dto.PlanMatSubstituteAllocationDTO allocation : substitutes) {
                    reservationLinks.add(new WmsMaterialReservationLinkDTO()
                            .setReservationNo(mrp.getMaterialReservationNo())
                            .setWarehouseCode(mrp.getDemandWarehouseCode()).setRequirementMatCode(mrp.getMatCode())
                            .setActualMatCode(allocation.getSubstituteCode())
                            .setSubstituteRatio(allocation.getSubstituteRatio())
                            .setReservedActualNum(allocation.getActualNum())
                            .setCertifiedSupplierCodes(allocation.getCertifiedSupplierCodes())
                            .setAllowedQualityResults(allocation.getAllowedQualityResults())
                            .setComplianceMode(allocation.getComplianceMode())
                            .setComplianceReferenceNo("DEVIATION".equalsIgnoreCase(allocation.getComplianceMode())
                                    ? allocation.getDeviationPermitNo() : allocation.getSupplierCertificateRef())
                            .setComplianceValidEnd("DEVIATION".equalsIgnoreCase(allocation.getComplianceMode())
                                    ? allocation.getDeviationValidEnd() : allocation.getSupplierCertificateValidEnd())
                            .setInspectionLevel(allocation.getInspectionLevel()));
                }
            } catch (Exception ex) {
                throw new CheckedException("计划[" + mrp.getPlanDemandNo() + "]物料预留快照损坏，禁止下达MES");
            }
        }
        return reservationLinks.stream().sorted(Comparator
                        .comparing(WmsMaterialReservationLinkDTO::getReservationNo,
                                Comparator.nullsFirst(String::compareTo))
                        .thenComparing(WmsMaterialReservationLinkDTO::getWarehouseCode,
                                Comparator.nullsFirst(String::compareTo))
                        .thenComparing(WmsMaterialReservationLinkDTO::getRequirementMatCode,
                                Comparator.nullsFirst(String::compareTo))
                        .thenComparing(WmsMaterialReservationLinkDTO::getActualMatCode,
                                Comparator.nullsFirst(String::compareTo))
                        .thenComparing(item -> Objects.toString(item.getReservedActualNum(), "")))
                .toList();
    }

    private WmsMaterialReservationLinkDTO copyReservationLink(WmsMaterialReservationLinkDTO source,
                                                              BigDecimal quantity) {
        return new WmsMaterialReservationLinkDTO().setReservationNo(source.getReservationNo())
                .setWarehouseCode(source.getWarehouseCode()).setRequirementMatCode(source.getRequirementMatCode())
                .setActualMatCode(source.getActualMatCode()).setSubstituteRatio(source.getSubstituteRatio())
                .setReservedActualNum(quantity).setCertifiedSupplierCodes(source.getCertifiedSupplierCodes())
                .setAllowedQualityResults(source.getAllowedQualityResults()).setComplianceMode(source.getComplianceMode())
                .setComplianceReferenceNo(source.getComplianceReferenceNo())
                .setComplianceValidEnd(source.getComplianceValidEnd()).setInspectionLevel(source.getInspectionLevel());
    }

    private void saveMesPlan(PlnIssueEntity plnIssueEntity, String workshopName, String workshopCode,
                             PlanMatMrpEntity matMrpEntity, List<PlanMatMrpEntity> demandMrpList,
                             PlnApsFormalContractResolver.Resolution formalResolution) {
        MesTaskReceiveDTO receive = new MesTaskReceiveDTO();
        receive.setPlanNo(plnIssueEntity.getIssueNo());
        receive.setMatCode(plnIssueEntity.getProductCode());
        receive.setMatName(plnIssueEntity.getProductName());
        receive.setMatSpec(plnIssueEntity.getSpecificationModel());
        receive.setNoticeNum(plnIssueEntity.getNum());
        receive.setWorkshopName(workshopName);
        receive.setWorkshopCode(workshopCode);
        receive.setFactoryCode(plnIssueEntity.getFactoryCode());
        applyFormalContract(receive, formalResolution, List.of(plnIssueEntity.getId()), plnIssueEntity);
        receive.setEngineeringReleaseId(plnIssueEntity.getEngineeringReleaseId());
        receive.setEngineeringContentHash(plnIssueEntity.getEngineeringContentHash());
        receive.setEngineeringVersionNo(plnIssueEntity.getEngineeringVersionNo());
        List<WmsMaterialReservationLinkDTO> reservationLinks = reservationLinksForIssue(demandMrpList);
        receive.setMaterialReservationSnapshot(cn.hutool.json.JSONUtil.toJsonStr(reservationLinks));
        if (ObjUtil.isNotEmpty(matMrpEntity)) {
            receive.setType(ObjUtil.equal(matMrpEntity.getSubitemBomId(), 0L) ? "PRODUCT" : "PART");
        } else {
            receive.setType("PRODUCT");
        }

        // 计划开工时间 = scheduledDate 当天第一班次开始，若为休息日则顺延到下一工作日
        LocalDate scheduledDate = plnIssueEntity.getScheduledDate() != null
                ? plnIssueEntity.getScheduledDate()
                : LocalDate.now();
        scheduledDate = nextWorkDay(scheduledDate);
        List<MdmClassesEntity> shifts = queryActiveShifts();
        LocalTime firstShiftStart = shifts.isEmpty()
                ? LocalTime.of(PLAN_START_HOUR, 0)
                : shifts.get(0).getStartTime();
        List<PlnSchedulingOperationEntity> operations = plnIssueEntity.getLockTaskId() == null ? List.of()
                : operationSchedulingService.listTaskOperations(plnIssueEntity.getLockTaskId(), plnIssueEntity.getId(), null);
        receive.setApsOperationSnapshot(cn.hutool.json.JSONUtil.toJsonStr(operations));
        LocalDateTime operationStart = operations.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
        LocalDateTime operationEnd = operations.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
        LocalDateTime planStart = operationStart != null ? operationStart : scheduledDate.atTime(firstShiftStart);
        receive.setPlanStartDate(toDate(planStart));

        // 计划完工时间：优先用工艺路线 productRate 计算，降级用需求日期 23:59
        LocalDateTime planEnd = operationEnd != null ? operationEnd : resolvePlanEndDate(
                plnIssueEntity.getProductCode(), plnIssueEntity.getNum(), planStart, plnIssueEntity.getDemandTime());
        receive.setPlanEndDate(toDate(planEnd));

        // 路线只能来自计划已绑定工程版本，MES会再向MDM精确取包并覆盖其他可编辑配置。
        receive.setProcessRouteCode(plnIssueEntity.getEngineeringRouteCode());

        sealMesIssueRequest(receive, "PLAN_ISSUE:" + plnIssueEntity.getId());
        R<MesTaskIssueReceiptDTO> response = remoteTaskService.add(receive);
        MesTaskIssueReceiptDTO receipt = response == null ? null : response.getData();
        if (response == null || !ObjUtil.equals(CommonConstants.SUCCESS, response.getCode()) || receipt == null
                || !Objects.equals(receive.getSourceRequestId(), receipt.getRequestId())
                || !Objects.equals(receive.getSourceRequestFingerprint(), receipt.getRequestFingerprint())
                || !Objects.equals(receive.getPlanNo(), receipt.getPlanNo())
                || receipt.getTaskId() == null || StrUtil.isBlank(receipt.getTaskCode())
                || receipt.getAcceptedTime() == null) {
            throw new CheckedException("生产模块推送失败");
        }
        plnIssueEntity.setMesIssueRequestId(receipt.getRequestId());
        plnIssueEntity.setMesIssueFingerprint(receipt.getRequestFingerprint());
        plnIssueEntity.setMesTaskId(receipt.getTaskId());
        plnIssueEntity.setMesTaskCode(receipt.getTaskCode());
        plnIssueEntity.setMesIssueTime(receipt.getAcceptedTime());
        if (plnIssueMapper.updateById(plnIssueEntity) != 1) {
            throw new CheckedException("MES接收成功，但生产计划回执保存失败；请使用原计划重新下达以幂等取回回执");
        }
    }

    /**
     * 把发布时核验的工厂策略与正式合同写入 MES 接收载荷。
     * 合批场景只能对应一个执行订单号；否则 MES 无法在一张工单上保持 ERP 订单追溯一致性。
     */
    private void applyFormalContract(MesTaskReceiveDTO receive,
                                     PlnApsFormalContractResolver.Resolution resolution,
                                     List<Long> issueIds, PlnIssueEntity representative) {
        if (resolution == null || resolution.policy() == null || resolution.contract() == null
                || representative == null || issueIds == null || issueIds.isEmpty()) {
            throw new CheckedException("MES下达缺少正式计划合同或工厂策略证据");
        }
        PlnFormalPlanContractDTO contract = resolution.contract();
        PlnFormalPlanContractDTO.Line contractLine = contract.getLines().stream()
                .filter(line -> "MAKE".equals(line.getSupplyType()))
                .filter(line -> Objects.equals(line.getSourceDemandNo(), representative.getSourceDemandNo()))
                .filter(line -> Objects.equals(line.getProductCode(), representative.getProductCode()))
                .findFirst().orElseThrow(() -> new CheckedException(
                        "生产计划[" + representative.getIssueNo() + "]没有匹配的正式合同自制行"));
        Set<String> productionOrders = issueIds.stream()
                .map(resolution.productionOrderByIssueId()::get)
                .filter(StrUtil::isNotBlank).collect(Collectors.toCollection(LinkedHashSet::new));
        if (productionOrders.size() != 1) {
            throw new CheckedException("一个MES执行单元只能对应一个正式生产订单；请拆分APS合批后下达");
        }
        receive.setFormalPlanContractId(parseLong(contractLine.getId(), "正式计划合同主键"));
        receive.setFormalPlanContractNo(contract.getContractNo());
        receive.setFormalPlanContractVersion(contract.getContractVersion());
        receive.setFormalPlanContractFingerprint(contract.getSourceContentFingerprint());
        receive.setSourceSystem(contract.getSourceSystemCode());
        receive.setSourcePlanNo(contract.getSourcePlanNo());
        receive.setSourcePlanVersion(contract.getSourcePlanVersion());
        receive.setPlanningPolicyId(parseLong(resolution.policy().getId(), "工厂计划权威策略主键"));
        receive.setPlanningPolicyVersion(resolution.policy().getVersionNo());
        receive.setPlanningPolicyFingerprint(resolution.policy().getContentFingerprint());
        receive.setMrpAuthority(resolution.policy().getMrpAuthority());
        receive.setProductionOrderSyncMode(resolution.policy().getProductionOrderSyncMode());
        String productionOrder = productionOrders.iterator().next();
        receive.setProductionOrder(productionOrder);
        BigDecimal productionOrderQuantity = issueIds.stream().distinct()
                .map(resolution.issueQuantityById()::get)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (productionOrderQuantity.signum() <= 0) {
            throw new CheckedException("正式生产订单总量无效");
        }
        receive.setProductionOrderQuantity(productionOrderQuantity);
        receive.setErpProductionOrderNo("ERP_ORDER_REQUIRED".equals(
                resolution.policy().getProductionOrderSyncMode()) ? productionOrder : null);
    }

    private static Long parseLong(String value, String label) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException("not positive");
            return parsed;
        } catch (RuntimeException exception) {
            throw new CheckedException(label + "格式不正确");
        }
    }

    /**
     * 在发送前冻结完整载荷指纹。请求号由业务主键生成，事务重试时保持不变；
     * 任一计划、资源、物料预留或工序快照变化都会形成不同指纹并被MES拒绝复用旧请求。
     */
    private void sealMesIssueRequest(MesTaskReceiveDTO receive, String requestId) {
        MesTaskReceiveRequestSigner.seal(receive, requestId);
    }

    /**
     * 计算计划完工时间（APS 标准：按工作日历 + 班次累计有效工时）。
     * <pre>
     *   需求工时(h) = ceil(数量 / 小时产率)
     *   从 planStart 开始，按工作日历跳过休息日，
     *   在每个工作日内按班次时间窗口累计可用工时，
     *   直到累计工时 >= 需求工时，返回对应时刻。
     * </pre>
     * 降级顺序：
     * 1. 无工艺路线 / productRate=0  → demandTime 23:59
     * 2. 日历/班次查询异常           → planStart + 需求工时（简单加法）
     * 3. 无 demandTime               → planStart+1天 23:59
     */
    private LocalDateTime resolvePlanEndDate(String productCode, BigDecimal num,
                                             LocalDateTime planStart, LocalDate demandTime) {
        if (productCode == null || num == null || num.compareTo(BigDecimal.ZERO) <= 0) {
            return fallbackEndDate(planStart, demandTime);
        }
        try {
            MdmProcessRouteEntity route = queryDefaultRoute(productCode);
            if (route == null || route.getProductRate() == null
                    || route.getProductRate().compareTo(BigDecimal.ZERO) <= 0) {
                return fallbackEndDate(planStart, demandTime);
            }
            long neededHours = num.divide(route.getProductRate(), 0, RoundingMode.CEILING).longValue();
            neededHours = Math.max(1, neededHours);

            // 查询班次列表（全部启用班次，按开始时间排序）
            List<MdmClassesEntity> shifts = queryActiveShifts();
            if (shifts.isEmpty()) {
                // 无班次配置：简单加法降级
                return planStart.plusHours(neededHours);
            }

            // 按工作日历累计工时
            return calcEndByCalendar(planStart, neededHours, shifts);

        } catch (Exception e) {
            log.warn("[排产下发] 工时计算异常，productCode={}，降级简单加法。原因: {}", productCode, e.getMessage());
            return fallbackEndDate(planStart, demandTime);
        }
    }

    /**
     * 按工作日历 + 班次，从 start 开始累计 neededHours 小时，返回完工时刻。
     * 最多向后推算 365 天，防止死循环（极端情况：日历全休）。
     */
    private LocalDateTime calcEndByCalendar(LocalDateTime start, long neededHours,
                                            List<MdmClassesEntity> shifts) {
        // 预取 60 天日历（不够再扩展）
        LocalDate rangeStart = start.toLocalDate();
        LocalDate rangeEnd = rangeStart.plusDays(60);
        Map<LocalDate, Boolean> workDayMap = fetchWorkDayMap(rangeStart, rangeEnd);

        long remainMinutes = neededHours * 60;
        LocalDateTime cursor = start;
        int safeGuard = 0;

        while (remainMinutes > 0 && safeGuard < 365) {
            LocalDate day = cursor.toLocalDate();

            // 超出预取范围则扩展
            if (day.isAfter(rangeEnd)) {
                rangeEnd = day.plusDays(60);
                workDayMap.putAll(fetchWorkDayMap(day, rangeEnd));
            }

            // 判断是否工作日（日历有记录以日历为准，否则默认周一~周五为工作日）
            boolean isWorkDay = workDayMap.containsKey(day)
                    ? workDayMap.get(day)
                    : (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY);

            if (!isWorkDay) {
                cursor = day.plusDays(1).atTime(shifts.get(0).getStartTime());
                safeGuard++;
                continue;
            }

            // 在当天班次内累计可用工时
            for (MdmClassesEntity shift : shifts) {
                LocalDateTime shiftStart = day.atTime(shift.getStartTime());
                LocalDateTime shiftEnd = shift.getEndTime().isBefore(shift.getStartTime())
                        ? day.plusDays(1).atTime(shift.getEndTime())   // 跨天班次
                        : day.atTime(shift.getEndTime());

                // cursor 在班次结束之后，跳过
                if (!cursor.isBefore(shiftEnd)) continue;

                // cursor 在班次开始之前，移到班次开始
                LocalDateTime effectiveStart = cursor.isBefore(shiftStart) ? shiftStart : cursor;
                long availableMinutes = java.time.Duration.between(effectiveStart, shiftEnd).toMinutes();

                if (availableMinutes <= 0) continue;

                if (availableMinutes >= remainMinutes) {
                    return effectiveStart.plusMinutes(remainMinutes);
                }
                remainMinutes -= availableMinutes;
                cursor = shiftEnd;
            }

            // 当天班次用完，移到下一天第一个班次开始
            cursor = day.plusDays(1).atTime(shifts.get(0).getStartTime());
            safeGuard++;
        }

        // 安全兜底
        return start.plusHours(neededHours);
    }

    /**
     * 查询日期范围内工作日历，返回 date→isWorkDay 的 Map。
     * 查询失败返回空 Map，由调用方用默认周历降级。
     */
    private Map<LocalDate, Boolean> fetchWorkDayMap(LocalDate start, LocalDate end) {
        try {
            R<List<MdmCalendarEntity>> resp = remoteCalendarService.listByRange(start, end);
            if (resp == null || !ObjUtil.equals(CommonConstants.SUCCESS, resp.getCode())
                    || resp.getData() == null) {
                return Collections.emptyMap();
            }
            Map<LocalDate, Boolean> map = new HashMap<>();
            for (MdmCalendarEntity c : resp.getData()) {
                map.put(c.getTime(), "0".equals(c.getStatus())); // 0=上班
            }
            return map;
        } catch (Exception e) {
            log.warn("[排产下发] 查询工作日历失败，降级使用自然周历。原因: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 查询所有启用班次，按开始时间升序。
     */
    private List<MdmClassesEntity> queryActiveShifts() {
        try {
            R<List<MdmClassesEntity>> resp = remoteClassesService.listActive();
            if (resp == null || !ObjUtil.equals(CommonConstants.SUCCESS, resp.getCode())
                    || resp.getData() == null) {
                return Collections.emptyList();
            }
            return resp.getData().stream()
                    .filter(s -> s.getStartTime() != null && s.getEndTime() != null)
                    .sorted(Comparator.comparing(MdmClassesEntity::getStartTime))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[排产下发] 查询班次失败，降级无班次模式。原因: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private LocalDateTime fallbackEndDate(LocalDateTime planStart, LocalDate demandTime) {
        if (demandTime != null) {
            return demandTime.atTime(23, 59, 59);
        }
        return planStart.toLocalDate().plusDays(1).atTime(23, 59, 59);
    }

    /**
     * 计算每天总可用产能分钟数（所有启用班次时长之和）。
     * 跨天班次（endTime < startTime）按跨天计算。
     * 无班次配置时降级为 WORK_HOURS_PER_DAY * 60。
     */
    private long calcDailyCapacityMinutes(List<MdmClassesEntity> shifts) {
        if (shifts == null || shifts.isEmpty()) {
            return (long) WORK_HOURS_PER_DAY * 60;
        }
        long total = 0;
        for (MdmClassesEntity shift : shifts) {
            if (shift.getStartTime() == null || shift.getEndTime() == null) continue;
            long minutes = java.time.Duration.between(shift.getStartTime(), shift.getEndTime()).toMinutes();
            if (minutes <= 0) minutes += 24 * 60; // 跨天班次
            total += minutes;
        }
        return total > 0 ? total : (long) WORK_HOURS_PER_DAY * 60;
    }

    /**
     * 计算单条计划所需生产分钟数，同时返回警告码。
     * 返回 long[]{neededMinutes, warningCode}
     * warningCode: 0=正常, 1=无工艺路线(降级60min), 2=产率未配置(降级60min)
     */
    private long[] calcNeededMinutesWithWarn(PlnIssueEntity issue) {
        if (issue.getNum() == null || issue.getNum().compareTo(BigDecimal.ZERO) <= 0) {
            return new long[]{60, 1};
        }
        try {
            MdmProcessRouteEntity route = queryDefaultRoute(issue.getProductCode());
            if (route == null) {
                return new long[]{60, 1};
            }
            if (route.getProductRate() == null || route.getProductRate().compareTo(BigDecimal.ZERO) <= 0) {
                return new long[]{60, 2};
            }
            long hours = issue.getNum().divide(route.getProductRate(), 0, RoundingMode.CEILING).longValue();
            return new long[]{Math.max(1, hours) * 60, 0};
        } catch (Exception e) {
            log.warn("[排产] 工时计算失败，productCode={}，降级60分钟。原因: {}", issue.getProductCode(), e.getMessage());
            return new long[]{60, 1};
        }
    }

    private long calcNeededMinutes(PlnIssueEntity issue, List<MdmClassesEntity> shifts) {
        return calcNeededMinutesWithWarn(issue)[0];
    }

    /**
     * 从 startDate 到 endDate 范围内收集工作日列表。
     * 若工作日数量不足 minCount，则向后延伸直到凑够为止（最多额外找 180 天）。
     * 至少返回 1 个工作日（兜底用 startDate）。
     */
    private List<LocalDate> buildWorkDaySequence(LocalDate startDate, LocalDate endDate, int minCount) {
        LocalDate rangeEnd = endDate.plusDays(Math.max(0, minCount));  // 预留足够范围
        Map<LocalDate, Boolean> workDayMap = fetchWorkDayMap(startDate, rangeEnd.plusDays(180));
        if (workDayMap.isEmpty()) {
            SchedulingContext.setWarning("工作日历未配置，已按自然周历（周一至周五）排产");
        }
        List<LocalDate> result = new ArrayList<>();
        LocalDate cursor = startDate;
        int safeGuard = 0;
        while (result.size() < Math.max(1, minCount) && safeGuard < 365) {
            boolean isWork = workDayMap.containsKey(cursor)
                    ? workDayMap.get(cursor)
                    : (cursor.getDayOfWeek() != DayOfWeek.SATURDAY && cursor.getDayOfWeek() != DayOfWeek.SUNDAY);
            if (isWork) {
                result.add(cursor);
            }
            cursor = cursor.plusDays(1);
            safeGuard++;
        }
        if (result.isEmpty()) {
            result.add(startDate); // 极端兜底
        }
        return result;
    }

    private LocalDate nextWorkDay(LocalDate date) {
        Map<LocalDate, Boolean> workDayMap = fetchWorkDayMap(date, date.plusDays(30));
        for (int i = 0; i < 30; i++) {
            LocalDate candidate = date.plusDays(i);
            boolean isWork = workDayMap.containsKey(candidate)
                    ? workDayMap.get(candidate)
                    : (candidate.getDayOfWeek() != DayOfWeek.SATURDAY
                    && candidate.getDayOfWeek() != DayOfWeek.SUNDAY);
            if (isWork) return candidate;
        }
        return date; // 极端兜底
    }

    /**
     * 从排产任务关联的规则集 ruleContent 中读取 dayCapacityThreshold。
     * ruleContent 示例: {"dayCapacityThreshold": 5}
     * 读不到或解析失败时降级为默认值 3。
     */
    private int resolveDayCapacityThreshold(PlnSchedulingTaskEntity task) {
        if (task == null || task.getRuleCode() == null) {
            return 3;
        }
        try {
            PlnSchedulingRuleSetEntity ruleSet = plnSchedulingRuleSetMapper.selectOne(
                    new LambdaQueryWrapper<PlnSchedulingRuleSetEntity>()
                            .eq(PlnSchedulingRuleSetEntity::getRuleCode, task.getRuleCode())
                            .eq(PlnSchedulingRuleSetEntity::getActive, Boolean.TRUE)
                            .last("LIMIT 1"));
            if (ruleSet == null || ruleSet.getRuleContent() == null) {
                return 3;
            }
            com.fasterxml.jackson.databind.JsonNode node = OBJECT_MAPPER.readTree(ruleSet.getRuleContent());
            com.fasterxml.jackson.databind.JsonNode threshold = node.get("dayCapacityThreshold");
            if (threshold != null && threshold.isInt() && threshold.asInt() > 0) {
                return threshold.asInt();
            }
        } catch (Exception e) {
            log.warn("[排产确认] 读取 dayCapacityThreshold 失败，降级为 3。原因: {}", e.getMessage());
        }
        return 3;
    }

    private MdmProcessRouteEntity queryDefaultRoute(String productCode) {
        if (productCode == null || productCode.isBlank()) {
            return null;
        }
        R<MdmProcessRouteEntity> resp = remoteProcessRouteService.getDefaultByProductCode(productCode);
        if (resp == null || !ObjUtil.equals(CommonConstants.SUCCESS, resp.getCode())) {
            return null;
        }
        return resp.getData();
    }

    /**
     * 查询产品默认工艺路线编码，找不到返回 null。
     */
    private String queryDefaultRouteCode(String productCode) {
        MdmProcessRouteEntity route = queryDefaultRoute(productCode);
        return route != null ? route.getRouteCode() : null;
    }

    /**
     * LocalDateTime → java.util.Date（时区 Asia/Shanghai）
     */
    private Date toDate(LocalDateTime ldt) {
        if (ldt == null) {
            return null;
        }
        return Date.from(ldt.atZone(ZoneId.of("Asia/Shanghai")).toInstant());
    }

    private PlnSchedulingTaskEntity buildTask(PlnSchedulingRunDTO runDTO,
                                              String status,
                                              PlnSchedulingRuleSetEntity activeRule,
                                              int planCount,
                                              LocalDate scheduleDate) {
        PlnSchedulingTaskEntity task = new PlnSchedulingTaskEntity();
        task.setExecutionId(com.kitex.mom.plan.service.SchedulingExecutionContext.executionId());
        task.setTaskNo(buildTaskNo(runDTO.getMode(), runDTO.getCycleType(), scheduleDate));
        task.setMode(runDTO.getMode());
        task.setTriggerType((runDTO.getTriggerType() == null || runDTO.getTriggerType().isBlank()) ? "MANUAL" : runDTO.getTriggerType());
        task.setCycleType(runDTO.getCycleType());
        task.setScheduleDate(scheduleDate);
        task.setTaskStatus(status);
        task.setPlanCount(planCount);
        if (activeRule != null) {
            task.setRuleCode(activeRule.getRuleCode());
            task.setRuleVersion(activeRule.getRuleVersion());
        } else {
            task.setRuleCode("DEFAULT_RULE");
            task.setRuleVersion(1);
        }
        return task;
    }

    private String buildTaskNo(String mode, String cycleType, LocalDate scheduleDate) {
        String modeCode = "simulate".equalsIgnoreCase(mode) ? "SIM" : "OFF";
        String cycleCode = "DAY";
        if ("WEEK".equalsIgnoreCase(cycleType)) {
            cycleCode = "WEEK";
        } else if ("MONTH".equalsIgnoreCase(cycleType)) {
            cycleCode = "MONTH";
        } else if (runAsTnRange(cycleType)) {
            cycleCode = "TN";
        }
        String dateCode = (scheduleDate == null ? LocalDate.now() : scheduleDate)
                .format(DateTimeFormatter.BASIC_ISO_DATE);
        String timeCode = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
        String randomCode = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "TASK-" + modeCode + "-" + cycleCode + "-" + dateCode + "-" + timeCode + "-" + randomCode;
    }

    private LocalDate resolveScheduleDate(PlnSchedulingRunDTO runDTO) {
        return runDTO.getScheduleDate() == null ? LocalDate.now() : runDTO.getScheduleDate();
    }

    private LocalDate getCycleStartDate(String cycleType, LocalDate baseDate) {
        if ("WEEK".equalsIgnoreCase(cycleType)) {
            return baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        } else if ("MONTH".equalsIgnoreCase(cycleType)) {
            return baseDate.withDayOfMonth(1);
        } else {
            return baseDate;
        }
    }

    /**
     * 排产分配的实际起始工作日（不早于今天）。
     * 查询计划范围用 getCycleStartDate，分配排产日期用此方法。
     */
    private LocalDate resolveScheduleStartDate(String cycleType, LocalDate baseDate) {
        LocalDate cycleStart = getCycleStartDate(cycleType, baseDate);
        LocalDate today = LocalDate.now();
        return cycleStart.isBefore(today) ? today : cycleStart;
    }

    private LocalDate getCycleEndDate(String cycleType, LocalDate baseDate, Integer planNum) {
        if ("WEEK".equalsIgnoreCase(cycleType)) {
            return baseDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        }
        if ("MONTH".equalsIgnoreCase(cycleType)) {
            return baseDate.with(TemporalAdjusters.lastDayOfMonth());
        }
        if (runAsTnRange(cycleType)) {
            int days = planNum == null ? defaultPlanNum() : Math.max(1, planNum);
            return baseDate.plusDays(days - 1L);
        }
        return baseDate;
    }

    private List<PlnIssueEntity> listUnissuedPlansInRange(LocalDate startDate, LocalDate endDate, List<Long> targetIds) {
        return listUnissuedPlansInRange(startDate, endDate, targetIds, false);
    }

    private List<PlnIssueEntity> listUnissuedPlansInRange(LocalDate startDate, LocalDate endDate, List<Long> targetIds, boolean bounded) {
        LambdaQueryWrapper<PlnIssueEntity> queryWrapper = new LambdaQueryWrapper<PlnIssueEntity>()
                .eq(PlnIssueEntity::getStatus, IssueStatusEnum.UNISSUED.getCode())
                .and(wrapper -> wrapper.isNull(PlnIssueEntity::getLockStatus).or().eq(PlnIssueEntity::getLockStatus, false))
                .and(wrapper -> wrapper
                        .between(PlnIssueEntity::getDemandTime, startDate, endDate)
                        .or()
                        .between(PlnIssueEntity::getScheduledDate, startDate, endDate));
        if (targetIds != null && !targetIds.isEmpty()) {
            queryWrapper.in(PlnIssueEntity::getId, targetIds);
        }
        if (bounded) queryWrapper.last("LIMIT 1001");
        return plnIssueMapper.selectList(queryWrapper
                .orderByAsc(PlnIssueEntity::getDemandTime)
                .orderByAsc(PlnIssueEntity::getIssueTime)
                .orderByAsc(PlnIssueEntity::getId));
    }

    private List<Long> normalizeTargetIds(Long issueId, List<Long> targetIds) {
        if (targetIds != null && !targetIds.isEmpty()) {
            List<Long> normalized = targetIds.stream().filter(Objects::nonNull).distinct().toList();
            if (normalized.isEmpty()) {
                throw new CheckedException("重排目标不能为空");
            }
            return normalized;
        }
        if (issueId != null) {
            return List.of(issueId);
        }
        throw new CheckedException("重排目标不能为空");
    }

    private List<Long> normalizeRunTargetIds(List<Long> targetIds) {
        if (targetIds == null) return null;
        if (targetIds.isEmpty() || targetIds.size() > 1000 || targetIds.stream().anyMatch(id -> id == null || id <= 0)
                || targetIds.stream().distinct().count() != targetIds.size())
            throw new CheckedException("显式排产来源须为1至1000个唯一正数计划ID；空列表不得扩大为整周期");
        return targetIds.stream().sorted().toList();
    }

    private PlnSchedulingRuleSetEntity requiredActiveRule() {
        var frozen = com.kitex.mom.plan.service.SchedulingExecutionContext.rule();
        if (frozen != null) return frozen;
        PlnSchedulingRuleSetEntity activeRule = ruleSetSelector.selectActiveRule();
        if (activeRule == null) {
            throw new CheckedException("未配置可用排产规则");
        }
        return activeRule;
    }

    private boolean runAsTnRange(String cycleType) {
        return "TN".equalsIgnoreCase(cycleType) || "T+N".equalsIgnoreCase(cycleType);
    }

    private List<PlnIssueEntity> filterAndSortByRule(List<PlnIssueEntity> plans,
                                                     PlnSchedulingRuleSetEntity activeRule,
                                                     LocalDate startDate,
                                                     LocalDate endDate,
                                                     String sequenceStrategy,
                                                     Integer crossPriorityWindowDays, String solverCode) {
        if (plans == null || plans.isEmpty()) {
            return Collections.emptyList();
        }
        applyMaterialConstraints(plans, startDate);
        int totalDays = Math.max(1, (int) java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1);
        List<MatchCandidate> candidates = new ArrayList<>();
        int index = 0;
        for (PlnIssueEntity issue : plans) {
            com.kitex.mom.plan.service.SchedulingExecutionContext.check();
            // 先评分，不提前分配日期
            RuleMatchResult result = ruleMatcher.match(issue, activeRule);
            if (result.isPassed()) {
                issue.setMatchedRules(toJson(result.getMatchedRules()));
                issue.setScoreBreakdown(toJson(result.getScoreBreakdown()));
                candidates.add(new MatchCandidate(issue, result.getSoftScore(), index));
            }
            index++;
        }
        // 按评分降序排列，评分相同时保持原始顺序
        candidates.sort(Comparator
                .comparingDouble(MatchCandidate::score).reversed()
                .thenComparingInt(MatchCandidate::sequence));
        // 按有限产能填充：根据工时负荷分配排产日期
        List<PlnIssueEntity> prioritySorted = candidates.stream().map(MatchCandidate::issue).toList();
        List<PlnIssueEntity> sorted = operationSchedulingService.optimizeGlobalSequence(prioritySorted,
                sequenceStrategy, crossPriorityWindowDays, startDate, solverCode);
        List<MdmClassesEntity> shifts = queryActiveShifts();
        long dailyCapacityMinutes = calcDailyCapacityMinutes(shifts);
        List<LocalDate> workDays = buildWorkDaySequence(startDate, endDate, sorted.size());

        int workDayIndex = 0;
        long remainMinutesToday = dailyCapacityMinutes;
        for (PlnIssueEntity issue : sorted) {
            if (workDayIndex >= workDays.size()) {
                LocalDate lastDay = workDays.get(workDays.size() - 1);
                List<LocalDate> more = buildWorkDaySequence(lastDay.plusDays(1), lastDay.plusDays(30), 1);
                workDays = new ArrayList<>(workDays);
                workDays.addAll(more);
            }
            long[] minutesAndWarn = calcNeededMinutesWithWarn(issue);
            long neededMinutes = minutesAndWarn[0];
            long warnCode = minutesAndWarn[1];
            // 将降级警告追加到 scoreBreakdown
            if (warnCode != 0) {
                String warnMsg = warnCode == 1 ? "未配置工艺路线，工时按默认1小时计算" : "工艺路线产率未配置，工时按默认1小时计算";
                try {
                    java.util.Map<String, Object> breakdown = issue.getScoreBreakdown() != null
                            ? new java.util.LinkedHashMap<>(OBJECT_MAPPER.readValue(issue.getScoreBreakdown(), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {
                    }))
                            : new java.util.LinkedHashMap<>();
                    breakdown.put("_warning", warnMsg);
                    issue.setScoreBreakdown(toJson(breakdown));
                } catch (Exception ignored) {
                }
            }
            // 新版逐工序物料约束由工序求解器按实际投料点推进；仅旧版快照沿用整单齐料日下界。
            int materialAlignedIndex = alignToMaterialAvailableDate(workDays, workDayIndex,
                    StrUtil.isBlank(issue.getOperationMaterialConstraintSnapshot())
                            ? issue.getMaterialAvailableDate() : null);
            if (materialAlignedIndex > workDayIndex) {
                workDayIndex = materialAlignedIndex;
                remainMinutesToday = dailyCapacityMinutes;
            }
            // 当天剩余不足且不是当天第一条，换下一个工作日
            if (remainMinutesToday <= 0) {
                workDayIndex++;
                remainMinutesToday = dailyCapacityMinutes;
                if (workDayIndex >= workDays.size()) {
                    LocalDate lastDay = workDays.get(workDays.size() - 1);
                    List<LocalDate> more = buildWorkDaySequence(lastDay.plusDays(1), lastDay.plusDays(30), 1);
                    workDays = new ArrayList<>(workDays);
                    workDays.addAll(more);
                }
            }
            issue.setScheduledDate(workDays.get(workDayIndex));
            // 扣减本条工时，跨天时推进 workDayIndex
            remainMinutesToday -= neededMinutes;
            while (remainMinutesToday < 0) {
                workDayIndex++;
                remainMinutesToday += dailyCapacityMinutes;
                if (workDayIndex >= workDays.size()) {
                    LocalDate lastDay = workDays.get(workDays.size() - 1);
                    List<LocalDate> more = buildWorkDaySequence(lastDay.plusDays(1), lastDay.plusDays(30), 1);
                    workDays = new ArrayList<>(workDays);
                    workDays.addAll(more);
                }
            }
            // 恰好用完当天产能，下一条从新的一天开始
            if (remainMinutesToday == 0) {
                issue.setScheduledEndDate(workDays.get(workDayIndex));
                workDayIndex++;
                remainMinutesToday = dailyCapacityMinutes;
                if (workDayIndex >= workDays.size()) {
                    LocalDate lastDay = workDays.get(workDays.size() - 1);
                    List<LocalDate> more = buildWorkDaySequence(lastDay.plusDays(1), lastDay.plusDays(30), 1);
                    workDays = new ArrayList<>(workDays);
                    workDays.addAll(more);
                }
            } else {
                issue.setScheduledEndDate(workDays.get(workDayIndex));
            }
        }
        // 分配完日期后，检查 scheduledEndDate 是否超过 demandTime
        for (PlnIssueEntity issue : sorted) {
            LocalDate endDate2 = issue.getScheduledEndDate();
            LocalDate demandTime = issue.getDemandTime();
            if (endDate2 != null && demandTime != null && endDate2.isAfter(demandTime)) {
                try {
                    java.util.Map<String, Object> breakdown = issue.getScoreBreakdown() != null
                            ? new java.util.LinkedHashMap<>(OBJECT_MAPPER.readValue(issue.getScoreBreakdown(), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {
                    }))
                            : new java.util.LinkedHashMap<>();
                    long overDays = java.time.temporal.ChronoUnit.DAYS.between(demandTime, endDate2);
                    breakdown.put("_endDateWarning", "预计完工" + endDate2 + "，超出需求日期" + overDays + "天");
                    issue.setScoreBreakdown(toJson(breakdown));
                } catch (Exception ignored) {
                }
            }
        }
        return sorted;
    }

    private void snapshotTaskDetails(Long taskId, List<PlnIssueEntity> plans) {
        if (taskId == null || plans == null || plans.isEmpty()) {
            return;
        }
        var evidence = plans.stream().map(PlnIssueEntity::getSequenceEvidenceJson).distinct().toList();
        if (evidence.size() != 1 || StrUtil.isBlank(evidence.get(0)))
            throw new CheckedException("新排产结果缺少唯一完整排序求解证据");
        var owner = plnSchedulingTaskMapper.selectById(taskId);
        if (owner == null || StrUtil.isBlank(owner.getFactoryCode()) || plans.stream().anyMatch(p -> !Objects.equals(p.getTenantId(), owner.getTenantId()) || !Objects.equals(p.getFactoryCode(), owner.getFactoryCode())))
            throw new CheckedException("排序结果与来源计划/任务工厂不一致");
        var proof = new PlnSchedulingSequenceProof();
        proof.setTenantId(owner.getTenantId());
        proof.setFactoryCode(owner.getFactoryCode());
        proof.setTaskId(taskId);
        var solver = JSONUtil.parseObj(evidence.get(0)).getJSONObject("solver");
        proof.setSolverCode(solver.getStr("code"));
        proof.setSolverVersion(solver.getStr("version"));
        proof.setEvidenceJson(evidence.get(0));
        proof.setFingerprint(com.kitex.mom.plan.service.solver.SequenceSolverRegistry.hash(evidence.get(0)));
        if (sequenceProofs.insert(proof) != 1) throw new CheckedException("排序证据保存失败，不能只保存任务结果");
        for (PlnIssueEntity issue : plans) {
            com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity detail = new com.kitex.mom.plan.entity.PlnSchedulingTaskDetailEntity();
            detail.setTaskId(taskId);
            detail.setIssueId(issue.getId());
            detail.setIssueNo(issue.getIssueNo());
            detail.setCustomerCode(issue.getCustomerCode());
            detail.setCustomerName(issue.getCustomerName());
            detail.setProductCode(issue.getProductCode());
            detail.setProductName(issue.getProductName());
            detail.setSpecificationModel(issue.getSpecificationModel());
            detail.setNum(issue.getNum());
            detail.setIssueTime(issue.getIssueTime());
            detail.setDemandTime(issue.getDemandTime());
            detail.setScheduledDate(issue.getScheduledDate());
            detail.setScheduledEndDate(issue.getScheduledEndDate());
            detail.setLockStatus(issue.getLockStatus());
            detail.setLockTaskId(issue.getLockTaskId());
            detail.setLockTime(issue.getLockTime());
            detail.setStatus(issue.getStatus());
            detail.setType(issue.getType());
            detail.setPlanType(issue.getPlanType());
            detail.setMatchedRules(issue.getMatchedRules());
            detail.setScoreBreakdown(issue.getScoreBreakdown());
            detail.setEngineeringReleaseId(issue.getEngineeringReleaseId());
            detail.setEngineeringContentHash(issue.getEngineeringContentHash());
            detail.setEngineeringVersionNo(issue.getEngineeringVersionNo());
            detail.setEngineeringRouteId(issue.getEngineeringRouteId());
            detail.setEngineeringBomId(issue.getEngineeringBomId());
            detail.setEngineeringBomVersion(issue.getEngineeringBomVersion());
            detail.setMaterialConstraintStatus(issue.getMaterialConstraintStatus());
            detail.setMaterialAvailableDate(issue.getMaterialAvailableDate());
            detail.setMaterialAvailableTime(issue.getMaterialAvailableTime());
            detail.setMaterialHardBlocked(issue.getMaterialHardBlocked());
            detail.setMaterialConstraintSummary(issue.getMaterialConstraintSummary());
            detail.setMaterialConstraintSnapshot(issue.getMaterialConstraintSnapshot());
            detail.setOperationMaterialConstraintSnapshot(issue.getOperationMaterialConstraintSnapshot());
            detail.setMaterialLineageFingerprint(issue.getMaterialLineageFingerprint());
            detail.setMaterialConstraintCheckedTime(issue.getMaterialConstraintCheckedTime());
            detail.setOperationScheduleStatus(issue.getOperationScheduleStatus());
            detail.setOperationHardBlocked(issue.getOperationHardBlocked());
            detail.setOperationScheduleSummary(issue.getOperationScheduleSummary());
            detail.setOperationCount(issue.getOperationCount());
            detail.setOperationStartTime(issue.getOperationStartTime());
            detail.setOperationEndTime(issue.getOperationEndTime());
            detail.setFactoryCode(issue.getFactoryCode());
            // APS只保存已经MDM核验的精确工程路线，不再二次选择“当前默认路线”。
            detail.setProcessRouteCode(issue.getEngineeringRouteCode());
            plnSchedulingTaskDetailMapper.insert(detail);
        }
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CheckedException("规则解释信息序列化失败");
        }
    }

    private void applyMaterialConstraints(List<PlnIssueEntity> issues, LocalDate baselineDate) {
        Map<Long, PlnMaterialConstraintEvaluationDTO> evaluations =
                materialConstraintService.evaluate(issues, baselineDate);
        for (PlnIssueEntity issue : issues) {
            PlnMaterialConstraintEvaluationDTO evaluation = evaluations.get(issue.getId());
            if (evaluation == null) {
                continue;
            }
            issue.setMaterialConstraintStatus(evaluation.getStatus());
            issue.setMaterialAvailableDate(evaluation.getMaterialAvailableDate());
            issue.setMaterialAvailableTime(evaluation.getMaterialAvailableTime());
            issue.setMaterialHardBlocked(evaluation.getHardBlocked());
            issue.setMaterialConstraintSummary(evaluation.getSummary());
            issue.setMaterialConstraintSnapshot(toJson(evaluation.getLines()));
            issue.setOperationMaterialConstraintSnapshot(toJson(evaluation.getOperationConstraints()));
            issue.setMaterialLineageFingerprint(evaluation.getBomPathFingerprint());
            issue.setMaterialConstraintCheckedTime(evaluation.getCheckedTime());
        }
    }

    private void refreshMaterialConstraints(List<PlnSchedulingTaskDetailEntity> details, LocalDate baselineDate) {
        List<Long> issueIds = details.stream().map(PlnSchedulingTaskDetailEntity::getIssueId)
                .filter(Objects::nonNull).distinct().toList();
        if (issueIds.isEmpty()) {
            return;
        }
        List<PlnIssueEntity> issues = plnIssueMapper.selectBatchIds(issueIds);
        Map<Long, PlnMaterialConstraintEvaluationDTO> evaluations =
                materialConstraintService.evaluate(issues, baselineDate);
        for (PlnSchedulingTaskDetailEntity detail : details) {
            PlnMaterialConstraintEvaluationDTO evaluation = evaluations.get(detail.getIssueId());
            if (evaluation == null) {
                continue;
            }
            detail.setMaterialConstraintStatus(evaluation.getStatus());
            detail.setMaterialAvailableDate(evaluation.getMaterialAvailableDate());
            detail.setMaterialAvailableTime(evaluation.getMaterialAvailableTime());
            detail.setMaterialHardBlocked(evaluation.getHardBlocked());
            detail.setMaterialConstraintSummary(evaluation.getSummary());
            detail.setMaterialConstraintSnapshot(toJson(evaluation.getLines()));
            detail.setOperationMaterialConstraintSnapshot(toJson(evaluation.getOperationConstraints()));
            detail.setMaterialLineageFingerprint(evaluation.getBomPathFingerprint());
            detail.setMaterialConstraintCheckedTime(evaluation.getCheckedTime());
            plnSchedulingTaskDetailMapper.updateById(detail);
        }
    }

    private List<PlnSchedulingCandidateProfileDTO> normalizeCandidateProfiles(
            List<PlnSchedulingCandidateProfileDTO> requested) {
        List<PlnSchedulingCandidateProfileDTO> profiles = requested == null || requested.isEmpty()
                ? defaultCandidateProfiles() : requested;
        if (profiles.size() < 2 || profiles.size() > 5) {
            throw new CheckedException("多候选排产必须配置2至5个目标方案");
        }
        Set<String> codes = new HashSet<>();
        List<PlnSchedulingCandidateProfileDTO> normalized = new ArrayList<>();
        for (PlnSchedulingCandidateProfileDTO source : profiles) {
            if (source == null || StrUtil.isBlank(source.getCandidateCode())
                    || StrUtil.isBlank(source.getCandidateName())) {
                throw new CheckedException("候选编码和名称不能为空");
            }
            PlnSchedulingCandidateProfileDTO profile = new PlnSchedulingCandidateProfileDTO();
            profile.setCandidateCode(source.getCandidateCode().trim().toUpperCase());
            profile.setCandidateName(source.getCandidateName().trim());
            // 省略新字段时仍省略序列化，保留既有永久请求指纹；新显式选择进入原请求内容校验。
            if (source.getSequenceSolverCode() != null) {
                String solver = sequenceSolvers.normalize(source.getSequenceSolverCode());
                if (!solver.matches("[A-Z0-9_]{2,100}")) throw new CheckedException("排序求解器编码无效");
                profile.setSequenceSolverCode(solver);
            }
            profile.setSequenceStrategy(StrUtil.blankToDefault(source.getSequenceStrategy(), "BALANCED_GLOBAL")
                    .trim().toUpperCase());
            profile.setCrossPriorityWindowDays(Math.max(0, Math.min(30,
                    Objects.requireNonNullElse(source.getCrossPriorityWindowDays(), 2))));
            profile.setDeliveryWeight(nonNegativeWeight(source.getDeliveryWeight(), "交期"));
            profile.setChangeoverWeight(nonNegativeWeight(source.getChangeoverWeight(), "换型"));
            profile.setCycleWeight(nonNegativeWeight(source.getCycleWeight(), "周期"));
            profile.setWaitingWeight(nonNegativeWeight(source.getWaitingWeight(), "等待"));
            profile.setLoadBalanceWeight(nonNegativeWeight(source.getLoadBalanceWeight(), "负载均衡"));
            profile.setWipWeight(nonNegativeWeight(source.getWipWeight(), "在制"));
            profile.setOvertimeWeight(nonNegativeWeight(source.getOvertimeWeight(), "加班"));
            profile.setDisturbanceWeight(nonNegativeWeight(source.getDisturbanceWeight(), "扰动"));
            if (!profile.getCandidateCode().matches("[A-Z0-9_]{2,30}")) {
                throw new CheckedException("候选编码仅支持2至30位大写字母、数字和下划线");
            }
            if (profile.getCandidateName().length() > 100) {
                throw new CheckedException("候选名称不能超过100位");
            }
            if (!codes.add(profile.getCandidateCode())) throw new CheckedException("候选编码不能重复");
            if (!Set.of("DELIVERY_FIRST", "BALANCED_GLOBAL", "CHANGEOVER_FIRST")
                    .contains(profile.getSequenceStrategy())) {
                throw new CheckedException("候选序列策略不支持：" + profile.getSequenceStrategy());
            }
            BigDecimal total = profile.getDeliveryWeight().add(profile.getChangeoverWeight())
                    .add(profile.getCycleWeight()).add(profile.getWaitingWeight())
                    .add(profile.getLoadBalanceWeight()).add(profile.getWipWeight())
                    .add(profile.getOvertimeWeight()).add(profile.getDisturbanceWeight());
            if (total.signum() <= 0) throw new CheckedException("候选目标权重合计必须大于0");
            normalized.add(profile);
        }
        return normalized;
    }

    private BigDecimal nonNegativeWeight(BigDecimal value, String label) {
        BigDecimal result = Objects.requireNonNullElse(value, BigDecimal.ZERO);
        if (result.signum() < 0 || result.compareTo(new BigDecimal("1000")) > 0) {
            throw new CheckedException(label + "目标权重必须在0至1000之间");
        }
        return result;
    }

    private List<PlnSchedulingCandidateProfileDTO> defaultCandidateProfiles() {
        return List.of(profile("DELIVERY", "交期优先", "DELIVERY_FIRST", 0,
                        45, 5, 10, 5, 5, 5, 15, 10),
                profile("BALANCED", "综合均衡", "BALANCED_GLOBAL", 2,
                        25, 15, 10, 10, 15, 10, 10, 5),
                profile("CHANGEOVER", "换型优先", "CHANGEOVER_FIRST", 7,
                        15, 35, 10, 5, 10, 5, 10, 10));
    }

    private PlnSchedulingCandidateProfileDTO profile(String code, String name, String strategy, int window,
                                                     int delivery, int changeover, int cycle, int waiting,
                                                     int loadBalance, int wip, int overtime, int disturbance) {
        PlnSchedulingCandidateProfileDTO result = new PlnSchedulingCandidateProfileDTO();
        result.setCandidateCode(code);
        result.setCandidateName(name);
        result.setSequenceStrategy(strategy);
        result.setCrossPriorityWindowDays(window);
        result.setDeliveryWeight(BigDecimal.valueOf(delivery));
        result.setChangeoverWeight(BigDecimal.valueOf(changeover));
        result.setCycleWeight(BigDecimal.valueOf(cycle));
        result.setWaitingWeight(BigDecimal.valueOf(waiting));
        result.setLoadBalanceWeight(BigDecimal.valueOf(loadBalance));
        result.setWipWeight(BigDecimal.valueOf(wip));
        result.setOvertimeWeight(BigDecimal.valueOf(overtime));
        result.setDisturbanceWeight(BigDecimal.valueOf(disturbance));
        return result;
    }

    @Override
    public String agentPermanentRequestFingerprint(PlnSchedulingScenarioRunDTO command) {
        command.setFactoryCode(StrUtil.blankToDefault(command.getFactoryCode(), "").trim());
        command.setTriggerType(StrUtil.blankToDefault(command.getTriggerType(), "AGENT").toUpperCase(Locale.ROOT));
        String cycle = StrUtil.blankToDefault(command.getCycleType(), "DAY").trim().toUpperCase(Locale.ROOT);
        command.setCycleType("T+N".equals(cycle) ? "TN" : cycle);
        return scenarioRequestFingerprint(command, normalizeCandidateProfiles(command.getCandidateProfiles()));
    }

    private String scenarioRequestFingerprint(PlnSchedulingScenarioRunDTO command,
                                              List<PlnSchedulingCandidateProfileDTO> profiles) {
        String raw = String.join("|", StrUtil.blankToDefault(command.getFactoryCode(), ""),
                StrUtil.blankToDefault(command.getTriggerType(), "MANUAL").toUpperCase(),
                StrUtil.blankToDefault(command.getCycleType(), "DAY").toUpperCase(),
                String.valueOf(command.getScheduleDate()), String.valueOf(command.getPlanNum()),
                toJson(Optional.ofNullable(command.getTargetIds()).orElse(List.of()).stream()
                        .filter(Objects::nonNull).sorted().toList()), toJson(profiles));
        // 省略新字段的历史命令保留原永久指纹，成功重放不重新捕获当前来源。
        if (command.getObjectiveTemplateId() != null) raw += "|OBJECTIVE_TEMPLATE:" + command.getObjectiveTemplateId();
        return sha256(command.getExpectedFingerprint() == null ? raw : raw + "|INPUT:" + command.getExpectedFingerprint());
    }

    private String sourceFingerprint(List<PlnIssueEntity> issues) {
        String raw = issues.stream().sorted(Comparator.comparing(PlnIssueEntity::getId))
                .map(item -> String.join("|", String.valueOf(item.getId()), String.valueOf(item.getIssueNo()),
                        String.valueOf(item.getProductCode()), String.valueOf(item.getNum()),
                        String.valueOf(item.getDemandTime()), String.valueOf(item.getScheduledDate()),
                        String.valueOf(item.getStatus()), String.valueOf(item.getLockStatus()),
                        String.valueOf(item.getUpdateTime())))
                .collect(Collectors.joining("\n"));
        return sha256(raw);
    }

    private String buildScenarioNo(LocalDate scheduleDate) {
        return "APS-SCN-" + Objects.requireNonNullElse(scheduleDate, LocalDate.now())
                .format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss")) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private PlnSchedulingScenarioDTO scenarioView(PlnSchedulingScenarioEntity scenario) {
        return new PlnSchedulingScenarioDTO().setScenario(scenario)
                .setCandidates(scenarioTasks(scenario.getId()));
    }

    private List<PlnSchedulingTaskEntity> scenarioTasks(Long scenarioId) {
        return plnSchedulingTaskMapper.selectList(new LambdaQueryWrapper<PlnSchedulingTaskEntity>()
                .eq(PlnSchedulingTaskEntity::getScenarioId, scenarioId)
                .orderByAsc(PlnSchedulingTaskEntity::getCandidateRank)
                .orderByAsc(PlnSchedulingTaskEntity::getId));
    }

    private List<Long> parseLongList(String json) {
        try {
            return OBJECT_MAPPER.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {
                    });
        } catch (Exception exception) {
            throw new CheckedException("排产情景来源计划快照损坏");
        }
    }

    private CandidateMetrics candidateMetrics(Long taskId, Map<Long, BaselinePlan> baselinePlans) {
        List<PlnSchedulingTaskDetailEntity> details = plnSchedulingTaskDetailMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                        .eq(PlnSchedulingTaskDetailEntity::getTaskId, taskId));
        List<PlnSchedulingOperationEntity> operations = operationSchedulingService
                .listTaskOperations(taskId, null, null);
        long hardBlocked = details.stream().filter(item -> Boolean.TRUE.equals(item.getOperationHardBlocked())
                || Boolean.TRUE.equals(item.getMaterialHardBlocked())).count();
        long lateCount = 0L;
        long totalLateDays = 0L;
        long maxLateDays = 0L;
        long onTimeCount = 0L;
        for (PlnSchedulingTaskDetailEntity detail : details) {
            if (detail.getOperationEndTime() == null || detail.getDemandTime() == null) continue;
            long late = Math.max(0L, java.time.temporal.ChronoUnit.DAYS.between(detail.getDemandTime(),
                    detail.getOperationEndTime().toLocalDate()));
            if (late > 0) {
                lateCount++;
                totalLateDays += late;
                maxLateDays = Math.max(maxLateDays, late);
            } else {
                onTimeCount++;
            }
        }
        LocalDateTime firstStart = operations.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
        LocalDateTime lastEnd = operations.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
        long makespan = firstStart == null || lastEnd == null ? 0L : Duration.between(firstStart, lastEnd).toMinutes();
        long active = operations.stream().map(PlnSchedulingOperationEntity::getActiveMinutes)
                .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        long changeover = operations.stream().map(PlnSchedulingOperationEntity::getChangeoverMinutes)
                .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        long changeoverCount = operations.stream().filter(item -> Optional.ofNullable(item.getChangeoverMinutes())
                .orElse(0L) > 0).count();
        long waiting = operations.stream().filter(item -> item.getIssueId() != null)
                .collect(Collectors.groupingBy(PlnSchedulingOperationEntity::getIssueId)).values().stream()
                .mapToLong(rows -> {
                    LocalDateTime start = rows.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                            .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
                    LocalDateTime end = rows.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                            .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
                    long span = start == null || end == null ? 0L : Duration.between(start, end).toMinutes();
                    long work = rows.stream().map(PlnSchedulingOperationEntity::getActiveMinutes)
                            .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
                    return Math.max(0L, span - work);
                }).sum();
        long fallbackCount = operations.stream().filter(item -> StrUtil.isNotBlank(item.getResourceType())
                && !"STATION".equals(item.getResourceType())).count();
        Map<Long, List<PlnSchedulingOperationEntity>> operationsByIssue = operations.stream()
                .filter(item -> item.getIssueId() != null)
                .collect(Collectors.groupingBy(PlnSchedulingOperationEntity::getIssueId));
        long wipExposure = 0L;
        List<WipBoundary> wipBoundaries = new ArrayList<>();
        for (List<PlnSchedulingOperationEntity> rows : operationsByIssue.values()) {
            LocalDateTime start = rows.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                    .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
            LocalDateTime end = rows.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                    .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
            if (start == null || end == null || !end.isAfter(start)) continue;
            wipExposure += Duration.between(start, end).toMinutes();
            wipBoundaries.add(new WipBoundary(start, 1));
            wipBoundaries.add(new WipBoundary(end, -1));
        }
        wipBoundaries.sort(Comparator.comparing(WipBoundary::time).thenComparingInt(WipBoundary::delta));
        int currentWip = 0;
        int peakWip = 0;
        for (WipBoundary boundary : wipBoundaries) {
            currentWip += boundary.delta;
            peakWip = Math.max(peakWip, currentWip);
        }

        Map<Long, PlnSchedulingTaskDetailEntity> detailByIssue = details.stream()
                .filter(item -> item.getIssueId() != null)
                .collect(Collectors.toMap(PlnSchedulingTaskDetailEntity::getIssueId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        long disturbance = 0L;
        long disturbanceCompared = 0L;
        for (Map.Entry<Long, BaselinePlan> entry : baselinePlans.entrySet()) {
            LocalDate baseline = entry.getValue().scheduledDate;
            PlnSchedulingTaskDetailEntity detail = detailByIssue.get(entry.getKey());
            if (baseline == null || detail == null || detail.getScheduledDate() == null) continue;
            disturbance += Math.abs(ChronoUnit.DAYS.between(baseline, detail.getScheduledDate()))
                    * WORK_HOURS_PER_DAY * 60L;
            disturbanceCompared++;
        }

        LoadMetrics load = loadMetrics(taskId, firstStart, lastEnd);
        return new CandidateMetrics(details.size(), hardBlocked, onTimeCount, lateCount, totalLateDays,
                maxLateDays, makespan, active, waiting, changeover, changeoverCount, fallbackCount,
                wipExposure, peakWip, load.imbalanceBasisPoints, load.peakUtilizationRate,
                load.averageUtilizationRate, load.requiredOvertimeMinutes, load.overloadedResourceDays,
                load.capacityUnverifiedResourceDays, load.resourceDayCount, load.coverageTruncated,
                load.hotspots, disturbance, disturbanceCompared, firstStart, lastEnd);
    }

    private LoadMetrics loadMetrics(Long taskId, LocalDateTime firstStart, LocalDateTime lastEnd) {
        if (firstStart == null || lastEnd == null) return LoadMetrics.empty();
        LocalDate start = firstStart.toLocalDate();
        LocalDate actualEnd = lastEnd.minusNanos(1).toLocalDate();
        LocalDate supportedEnd = start.plusDays(92);
        boolean truncated = actualEnd.isAfter(supportedEnd);
        PlnResourceLoadQueryDTO query = new PlnResourceLoadQueryDTO();
        query.setTaskId(taskId);
        query.setStartDate(start);
        query.setEndDate(truncated ? supportedEnd : actualEnd);
        List<PlnResourceLoadVO> loads = resourceLoadService.queryDailyLoads(query);
        if (loads.isEmpty()) return new LoadMetrics(0L, BigDecimal.ZERO, BigDecimal.ZERO,
                0L, 0L, 0L, 0L, truncated, List.of());
        List<BigDecimal> rates = loads.stream().map(PlnResourceLoadVO::getUtilizationRate)
                .filter(Objects::nonNull).toList();
        BigDecimal average = rates.isEmpty() ? BigDecimal.ZERO : rates.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(rates.size()), 4, RoundingMode.HALF_UP);
        BigDecimal variance = rates.isEmpty() ? BigDecimal.ZERO : rates.stream()
                .map(rate -> rate.subtract(average).pow(2)).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(rates.size()), 8, RoundingMode.HALF_UP);
        long imbalance = Math.round(Math.sqrt(variance.doubleValue()) * 100D);
        BigDecimal peak = rates.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        long overtime = loads.stream().map(PlnResourceLoadVO::getOverloadMinutes)
                .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        long overloaded = loads.stream().filter(item -> Optional.ofNullable(item.getOverloadMinutes()).orElse(0L) > 0).count();
        long unverified = loads.stream().filter(item -> !Boolean.TRUE.equals(item.getCapacityVerified())).count();
        List<ResourceHotspotSnapshot> hotspots = loads.stream()
                .sorted(Comparator
                        .comparingLong((PlnResourceLoadVO item) -> Optional.ofNullable(item.getOverloadMinutes())
                                .orElse(0L)).reversed()
                        .thenComparing(item -> Boolean.TRUE.equals(item.getCapacityVerified()))
                        .thenComparing(PlnResourceLoadVO::getUtilizationRate,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PlnResourceLoadVO::getPlannedMinutes,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PlnResourceLoadVO::getLoadDate)
                        .thenComparing(PlnResourceLoadVO::getResourceCode,
                                Comparator.nullsLast(String::compareTo)))
                .limit(12)
                .map(ResourceHotspotSnapshot::from)
                .toList();
        return new LoadMetrics(imbalance, peak, average, overtime, overloaded, unverified,
                loads.size(), truncated, hotspots);
    }

    private void rankCandidates(List<CandidateEvaluation> evaluations) {
        long minLate = evaluations.stream().mapToLong(item -> item.metrics.totalLateDays).min().orElse(0L);
        long maxLate = evaluations.stream().mapToLong(item -> item.metrics.totalLateDays).max().orElse(0L);
        long minChange = evaluations.stream().mapToLong(item -> item.metrics.changeoverMinutes).min().orElse(0L);
        long maxChange = evaluations.stream().mapToLong(item -> item.metrics.changeoverMinutes).max().orElse(0L);
        long minCycle = evaluations.stream().mapToLong(item -> item.metrics.makespanMinutes).min().orElse(0L);
        long maxCycle = evaluations.stream().mapToLong(item -> item.metrics.makespanMinutes).max().orElse(0L);
        long minWaiting = evaluations.stream().mapToLong(item -> item.metrics.waitingMinutes).min().orElse(0L);
        long maxWaiting = evaluations.stream().mapToLong(item -> item.metrics.waitingMinutes).max().orElse(0L);
        long minLoad = evaluations.stream().mapToLong(item -> item.metrics.loadImbalanceBasisPoints).min().orElse(0L);
        long maxLoad = evaluations.stream().mapToLong(item -> item.metrics.loadImbalanceBasisPoints).max().orElse(0L);
        long minWip = evaluations.stream().mapToLong(item -> item.metrics.wipExposureMinutes).min().orElse(0L);
        long maxWip = evaluations.stream().mapToLong(item -> item.metrics.wipExposureMinutes).max().orElse(0L);
        long minOvertime = evaluations.stream().mapToLong(item -> item.metrics.requiredOvertimeMinutes).min().orElse(0L);
        long maxOvertime = evaluations.stream().mapToLong(item -> item.metrics.requiredOvertimeMinutes).max().orElse(0L);
        long minDisturbance = evaluations.stream().mapToLong(item -> item.metrics.disturbanceMinutes).min().orElse(0L);
        long maxDisturbance = evaluations.stream().mapToLong(item -> item.metrics.disturbanceMinutes).max().orElse(0L);
        for (CandidateEvaluation item : evaluations) {
            BigDecimal deliveryPenalty = normalizedPenalty(item.metrics.totalLateDays, minLate, maxLate);
            BigDecimal changeoverPenalty = normalizedPenalty(item.metrics.changeoverMinutes, minChange, maxChange);
            BigDecimal cyclePenalty = normalizedPenalty(item.metrics.makespanMinutes, minCycle, maxCycle);
            BigDecimal waitingPenalty = normalizedPenalty(item.metrics.waitingMinutes, minWaiting, maxWaiting);
            BigDecimal loadPenalty = normalizedPenalty(item.metrics.loadImbalanceBasisPoints, minLoad, maxLoad);
            BigDecimal wipPenalty = normalizedPenalty(item.metrics.wipExposureMinutes, minWip, maxWip);
            BigDecimal overtimePenalty = normalizedPenalty(item.metrics.requiredOvertimeMinutes, minOvertime, maxOvertime);
            BigDecimal disturbancePenalty = normalizedPenalty(item.metrics.disturbanceMinutes, minDisturbance, maxDisturbance);
            BigDecimal totalWeight = item.profile.getDeliveryWeight().add(item.profile.getChangeoverWeight())
                    .add(item.profile.getCycleWeight()).add(item.profile.getWaitingWeight())
                    .add(item.profile.getLoadBalanceWeight()).add(item.profile.getWipWeight())
                    .add(item.profile.getOvertimeWeight()).add(item.profile.getDisturbanceWeight());
            BigDecimal weightedPenalty = deliveryPenalty.multiply(item.profile.getDeliveryWeight())
                    .add(changeoverPenalty.multiply(item.profile.getChangeoverWeight()))
                    .add(cyclePenalty.multiply(item.profile.getCycleWeight()))
                    .add(waitingPenalty.multiply(item.profile.getWaitingWeight()))
                    .add(loadPenalty.multiply(item.profile.getLoadBalanceWeight()))
                    .add(wipPenalty.multiply(item.profile.getWipWeight()))
                    .add(overtimePenalty.multiply(item.profile.getOvertimeWeight()))
                    .add(disturbancePenalty.multiply(item.profile.getDisturbanceWeight()))
                    .divide(totalWeight, 4, RoundingMode.HALF_UP);
            BigDecimal score = new BigDecimal("100").subtract(weightedPenalty);
            if (item.metrics.hardBlockedCount > 0) score = score.subtract(new BigDecimal("50"));
            item.task.setCandidateScore(score.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
            Map<String, Object> snapshot = item.metrics.toMap();
            snapshot.put("normalizedDeliveryPenalty", deliveryPenalty);
            snapshot.put("normalizedChangeoverPenalty", changeoverPenalty);
            snapshot.put("normalizedCyclePenalty", cyclePenalty);
            snapshot.put("normalizedWaitingPenalty", waitingPenalty);
            snapshot.put("normalizedLoadBalancePenalty", loadPenalty);
            snapshot.put("normalizedWipPenalty", wipPenalty);
            snapshot.put("normalizedOvertimePenalty", overtimePenalty);
            snapshot.put("normalizedDisturbancePenalty", disturbancePenalty);
            snapshot.put("score", item.task.getCandidateScore());
            item.task.setCandidateMetricsSnapshot(toJson(snapshot));
        }
        List<CandidateEvaluation> ranked = evaluations.stream().sorted(Comparator
                .comparing((CandidateEvaluation item) -> item.metrics.hardBlockedCount > 0)
                .thenComparing((CandidateEvaluation item) -> item.task.getCandidateScore(), Comparator.reverseOrder())
                .thenComparing(item -> item.task.getCandidateCode())).toList();
        for (int index = 0; index < ranked.size(); index++) {
            ranked.get(index).task.setCandidateRank(index + 1);
            ranked.get(index).task.setTaskStatus("WAIT_CONFIRM");
            plnSchedulingTaskMapper.updateById(ranked.get(index).task);
        }
    }

    private BigDecimal normalizedPenalty(long value, long min, long max) {
        if (max <= min) return BigDecimal.ZERO;
        return BigDecimal.valueOf(value - min).multiply(new BigDecimal("100"))
                .divide(BigDecimal.valueOf(max - min), 4, RoundingMode.HALF_UP);
    }

    private String currentUsername() {
        try {
            return SecurityUtils.getUser().getUsername();
        } catch (Exception exception) {
            return "SYSTEM";
        }
    }

    private String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("排产情景摘要生成失败");
        }
    }

    /**
     * 物料日期是生产开工的下界。若齐料日在当前产能游标之后，直接把游标推进到对应工作日。
     */
    private int alignToMaterialAvailableDate(List<LocalDate> workDays, int currentIndex,
                                             LocalDate materialAvailableDate) {
        if (materialAvailableDate == null || workDays.isEmpty()
                || !workDays.get(Math.min(currentIndex, workDays.size() - 1)).isBefore(materialAvailableDate)) {
            return currentIndex;
        }
        LocalDate target = nextWorkDay(materialAvailableDate);
        while (workDays.get(workDays.size() - 1).isBefore(target)) {
            LocalDate last = workDays.get(workDays.size() - 1);
            LocalDate next = nextWorkDay(last.plusDays(1));
            if (!next.isAfter(last)) {
                next = last.plusDays(1);
            }
            workDays.add(next);
        }
        for (int i = currentIndex; i < workDays.size(); i++) {
            if (!workDays.get(i).isBefore(target)) {
                return i;
            }
        }
        return currentIndex;
    }

    @Override
    public LocalDate calcScheduledEndDate(String productCode, BigDecimal num, LocalDate startDate) {
        if (startDate == null) return LocalDate.now();
        if (productCode == null || num == null || num.compareTo(BigDecimal.ZERO) <= 0) {
            return startDate;
        }
        try {
            MdmProcessRouteEntity route = queryDefaultRoute(productCode);
            if (route == null || route.getProductRate() == null
                    || route.getProductRate().compareTo(BigDecimal.ZERO) <= 0) {
                return startDate;
            }
            long neededHours = num.divide(route.getProductRate(), 0, RoundingMode.CEILING).longValue();
            neededHours = Math.max(1, neededHours);
            List<MdmClassesEntity> shifts = queryActiveShifts();
            long dailyCapacityMinutes = calcDailyCapacityMinutes(shifts);
            if (dailyCapacityMinutes <= 0) return startDate;
            // 从 startDate 开始，按工作日顺延，直到消耗完所需工时
            long remainMinutes = neededHours * 60;
            List<LocalDate> workDays = buildWorkDaySequence(startDate, startDate.plusDays(Math.max(30, (int) (remainMinutes / dailyCapacityMinutes) + 5)), (int) (remainMinutes / dailyCapacityMinutes) + 2);
            int idx = 0;
            long remain = remainMinutes;
            while (remain > 0 && idx < workDays.size()) {
                remain -= dailyCapacityMinutes;
                if (remain > 0) idx++;
            }
            return workDays.get(Math.min(idx, workDays.size() - 1));
        } catch (Exception e) {
            log.warn("[排产调整] 结束日期计算失败，productCode={}，降级返回 startDate。原因: {}", productCode, e.getMessage());
            return startDate;
        }
    }

    private int defaultPlanNum() {
        return 7;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean savePlanDateBatch(List<PlnSchedulingTaskDetailEntity> PlnSchedulingTaskDetailEntity) {
        // 空集合直接返回
        if (CollUtil.isEmpty(PlnSchedulingTaskDetailEntity)) {
            return false;
        }
        // 批量更新
        return plnProductDemandMapper.updatePlanDateBatch(PlnSchedulingTaskDetailEntity) > 0;
    }

    private record MatchCandidate(PlnIssueEntity issue, double score, int sequence) {
    }

    private record CandidateEvaluation(PlnSchedulingTaskEntity task, CandidateMetrics metrics,
                                       PlnSchedulingCandidateProfileDTO profile) {
    }

    private record BaselinePlan(LocalDate scheduledDate) {
    }

    private record WipBoundary(LocalDateTime time, int delta) {
    }

    private record LoadMetrics(long imbalanceBasisPoints, BigDecimal peakUtilizationRate,
                               BigDecimal averageUtilizationRate, long requiredOvertimeMinutes,
                               long overloadedResourceDays, long capacityUnverifiedResourceDays,
                               long resourceDayCount, boolean coverageTruncated,
                               List<ResourceHotspotSnapshot> hotspots) {
        private static LoadMetrics empty() {
            return new LoadMetrics(0L, BigDecimal.ZERO, BigDecimal.ZERO,
                    0L, 0L, 0L, 0L, false, List.of());
        }
    }

    private record ResourceHotspotSnapshot(String resourceType, String resourceCode, String resourceName,
                                           LocalDate loadDate, BigDecimal utilizationRate,
                                           Long plannedMinutes, Long availableMinutes, Long overloadMinutes,
                                           String loadStatus, Boolean capacityVerified, String capacityExplanation,
                                           Integer operationCount, Integer issueCount) {
        private static ResourceHotspotSnapshot from(PlnResourceLoadVO source) {
            return new ResourceHotspotSnapshot(source.getResourceType(), source.getResourceCode(),
                    source.getResourceName(), source.getLoadDate(), source.getUtilizationRate(),
                    source.getPlannedMinutes(), source.getAvailableMinutes(), source.getOverloadMinutes(),
                    source.getLoadStatus(), source.getCapacityVerified(), source.getCapacityExplanation(),
                    source.getOperationCount(), source.getIssueCount());
        }

        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("resourceType", resourceType);
            result.put("resourceCode", resourceCode);
            result.put("resourceName", resourceName);
            result.put("loadDate", loadDate == null ? null : loadDate.toString());
            result.put("utilizationRate", utilizationRate);
            result.put("plannedMinutes", plannedMinutes);
            result.put("availableMinutes", availableMinutes);
            result.put("overloadMinutes", overloadMinutes);
            result.put("loadStatus", loadStatus);
            result.put("capacityVerified", capacityVerified);
            result.put("capacityExplanation", capacityExplanation);
            result.put("operationCount", operationCount);
            result.put("issueCount", issueCount);
            return result;
        }
    }

    private record CandidateMetrics(long planCount, long hardBlockedCount, long onTimeCount, long lateCount,
                                    long totalLateDays, long maxLateDays, long makespanMinutes,
                                    long activeMinutes, long waitingMinutes, long changeoverMinutes,
                                    long changeoverCount, long fallbackResourceCount,
                                    long wipExposureMinutes, int peakWipCount,
                                    long loadImbalanceBasisPoints, BigDecimal peakUtilizationRate,
                                    BigDecimal averageUtilizationRate, long requiredOvertimeMinutes,
                                    long overloadedResourceDays, long capacityUnverifiedResourceDays,
                                    long resourceDayCount, boolean loadCoverageTruncated,
                                    List<ResourceHotspotSnapshot> resourceHotspots,
                                    long disturbanceMinutes, long disturbanceComparedPlanCount,
                                    LocalDateTime scheduledStartTime, LocalDateTime scheduledEndTime) {
        private Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("planCount", planCount);
            result.put("hardBlockedCount", hardBlockedCount);
            result.put("onTimeCount", onTimeCount);
            result.put("lateCount", lateCount);
            result.put("totalLateDays", totalLateDays);
            result.put("maxLateDays", maxLateDays);
            result.put("makespanMinutes", makespanMinutes);
            result.put("activeMinutes", activeMinutes);
            result.put("waitingMinutes", waitingMinutes);
            result.put("changeoverMinutes", changeoverMinutes);
            result.put("changeoverCount", changeoverCount);
            result.put("fallbackResourceCount", fallbackResourceCount);
            result.put("wipExposureMinutes", wipExposureMinutes);
            result.put("peakWipCount", peakWipCount);
            result.put("loadImbalanceBasisPoints", loadImbalanceBasisPoints);
            result.put("loadImbalanceRate", BigDecimal.valueOf(loadImbalanceBasisPoints)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
            result.put("peakUtilizationRate", peakUtilizationRate);
            result.put("averageUtilizationRate", averageUtilizationRate);
            result.put("requiredOvertimeMinutes", requiredOvertimeMinutes);
            result.put("overloadedResourceDays", overloadedResourceDays);
            result.put("capacityUnverifiedResourceDays", capacityUnverifiedResourceDays);
            result.put("resourceDayCount", resourceDayCount);
            result.put("loadCoverageTruncated", loadCoverageTruncated);
            result.put("resourceHotspots", resourceHotspots.stream()
                    .map(ResourceHotspotSnapshot::toMap).toList());
            result.put("disturbanceMinutes", disturbanceMinutes);
            result.put("disturbanceComparedPlanCount", disturbanceComparedPlanCount);
            result.put("scheduledStartTime", scheduledStartTime == null ? null : scheduledStartTime.toString());
            result.put("scheduledEndTime", scheduledEndTime == null ? null : scheduledEndTime.toString());
            return result;
        }
    }
}
