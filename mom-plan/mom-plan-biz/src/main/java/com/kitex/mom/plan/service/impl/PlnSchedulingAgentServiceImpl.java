package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesPlanSourceMappingDTO;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningPolicyDTO;
import com.kitex.mom.plan.agent.dto.PlanProductionScheduleCommitmentDTO;
import com.kitex.mom.plan.dto.*;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import com.kitex.mom.plan.service.PlnOperationSchedulingService;
import com.kitex.mom.plan.service.PlnFactoryPlanningAuthorityService;
import com.kitex.mom.plan.service.PlnFormalPlanContractService;
import com.kitex.mom.plan.service.PlnSchedulingAgentService;
import com.kitex.mom.plan.service.PlnSchedulingEffectReviewService;
import com.kitex.mom.plan.service.PlnSchedulingEngineService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent只通过此服务编排确定性APS。模型不计算工序时刻，也不能绕过情景选择和A3锁定。
 */
@Service
@RequiredArgsConstructor
public class PlnSchedulingAgentServiceImpl implements PlnSchedulingAgentService {
    private static final int MAX_PLAN_IMPACTS = 20;
    private static final int MAX_EFFECT_DEVIATIONS = 30;
    private static final int MAX_EFFECT_DATA_GAPS = 20;
    private static final int MAX_COMMITMENT_PLANS = 20;
    private static final int MAX_COMMITMENT_OPERATIONS = 80;
    private static final String PRODUCTION_COMMITMENT_TOOL =
            "PLAN_PRODUCTION_SCHEDULE_COMMITMENT_QUERY@1";

    private final PlnSchedulingEngineService schedulingEngineService;
    private final PlnOperationSchedulingService operationSchedulingService;
    private final PlnSchedulingScenarioMapper scenarioMapper;
    private final PlnApsCaseMapper apsCaseMapper;
    private final PlnIssueMapper issueMapper;
    private final PlnSchedulingTaskMapper schedulingTaskMapper;
    private final PlnSchedulingOperationMapper schedulingOperationMapper;
    private final PlnSchedulingTaskDetailMapper taskDetailMapper;
    private final PlnObjectiveTemplateMapper objectiveTemplateMapper;
    private final PlnObjectiveTemplateService objectiveTemplateService;
    private final PlnSchedulingEffectReviewService effectReviewService;
    private final RemoteMesTaskService remoteMesTaskService;
    private final PlnFactoryPlanningAuthorityService planningAuthorityService;
    private final PlnFormalPlanContractService formalPlanContractService;
    private final ObjectMapper objectMapper;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    public PlanSchedulingAgentDTO.Readiness preview(PlnSchedulingScenarioRunDTO command) {
        require(command != null && StrUtil.isNotBlank(command.getFactoryCode()), "排产预检必须指定工厂");
        requireFactory(command.getFactoryCode());
        try {
            validateApsEvidenceEnvelope(command);
            PlnSchedulingScenarioInput input = schedulingEngineService.captureScenario(command);
            List<String> sourcePlanNos = input.getSources().stream()
                    .map(item -> StrUtil.blankToDefault(item.getIssueNo(), String.valueOf(item.getId())))
                    .limit(20)
                    .toList();
            List<String> candidateNames = input.getRun().getCandidateProfiles().stream()
                    .map(item -> StrUtil.blankToDefault(item.getCandidateName(), item.getCandidateCode()))
                    .toList();
            PlanSchedulingAgentDTO.ObjectiveTemplateOption objectiveTemplate = templateFromSnapshot(
                    input.getObjectiveTemplateSnapshot());
            return new PlanSchedulingAgentDTO.Readiness()
                    .setReady(true)
                    .setFactoryCode(input.getRun().getFactoryCode())
                    .setScheduleDate(input.getRun().getScheduleDate())
                    .setCycleType(input.getRun().getCycleType())
                    .setSourcePlanCount(input.getSources().size())
                    .setCandidateCount(candidateNames.size())
                    .setCandidateNames(candidateNames)
                    .setRuleCode(input.getRule().getRuleCode())
                    .setRuleVersion(input.getRule().getRuleVersion())
                    .setObjectiveTemplateVersionRef(templateVersionRef(objectiveTemplate))
                    .setObjectiveTemplateName(objectiveTemplate == null ? null : objectiveTemplate.getTemplateName())
                    .setIndustryCode(objectiveTemplate == null ? null : objectiveTemplate.getIndustryCode())
                    .setSourcePlanNos(sourcePlanNos)
                    .setSummary("预检通过：可基于" + input.getSources().size() + "个计划生成"
                            + candidateNames.size() + "个确定性排产候选")
                    .setCapturedTime(LocalDateTime.now());
        } catch (CheckedException ex) {
            return new PlanSchedulingAgentDTO.Readiness()
                    .setReady(false)
                    .setFactoryCode(command.getFactoryCode().trim())
                    .setScheduleDate(command.getScheduleDate())
                    .setCycleType(StrUtil.blankToDefault(command.getCycleType(), "DAY").toUpperCase(Locale.ROOT))
                    .setSourcePlanCount(0)
                    .setCandidateCount(0)
                    .setCandidateNames(List.of())
                    .setSourcePlanNos(List.of())
                    .setSummary(ex.getMessage())
                    .setCapturedTime(LocalDateTime.now());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanSchedulingAgentDTO.ScenarioEvidence run(PlnSchedulingScenarioRunDTO command) {
        require(command != null && StrUtil.isNotBlank(command.getRequestId())
                && StrUtil.isNotBlank(command.getFactoryCode()), "Agent排产必须冻结请求号和工厂");
        requireFactory(command.getFactoryCode());
        validateApsEvidenceEnvelope(command);
        command.setFactoryCode(command.getFactoryCode().trim());
        command.setTriggerType(StrUtil.blankToDefault(command.getTriggerType(), "AGENT").toUpperCase(Locale.ROOT));
        PlnSchedulingScenarioDTO result = schedulingEngineService.runScenario(command);
        require(result != null && result.getScenario() != null
                        && Objects.equals(command.getFactoryCode(), result.getScenario().getFactoryCode()),
                "APS情景工厂与Agent冻结范围不一致");
        return evidence(result);
    }

    /**
     * 案件工作台必须完整携带案件、快照和资源指纹；旧排产入口三者均不传时保持兼容。
     * 真实归属和当前性由案件门面复核，这里阻止半套证据绕过门面进入求解快照。
     */
    private void validateApsEvidenceEnvelope(PlnSchedulingScenarioRunDTO command) {
        boolean apsRequest = command.getApsCaseId() != null || command.getResourceSnapshotId() != null
                || StrUtil.isNotBlank(command.getExpectedResourceFingerprint());
        if (!apsRequest) return;
        require(command.getApsCaseId() != null && command.getApsCaseId() > 0,
                "智能APS求解缺少案件主键");
        require(command.getResourceSnapshotId() != null && command.getResourceSnapshotId() > 0,
                "智能APS求解缺少资源快照主键");
        require(command.getExpectedResourceFingerprint() != null
                        && command.getExpectedResourceFingerprint().matches("[0-9a-f]{64}"),
                "智能APS求解缺少有效资源证据指纹");
    }

    @Override
    public PlanSchedulingAgentDTO.ScenarioEvidence query(PlanSchedulingAgentDTO.ScenarioQuery query) {
        require(query != null && StrUtil.isNotBlank(query.getFactoryCode())
                        && (query.getScenarioId() != null || StrUtil.isNotBlank(query.getScenarioNo())),
                "排产情景查询必须指定工厂及情景ID或编号");
        requireFactory(query.getFactoryCode());
        PlnSchedulingScenarioDTO result = query.getScenarioId() != null
                ? schedulingEngineService.getScenarioById(query.getScenarioId())
                : schedulingEngineService.getScenario(query.getScenarioNo().trim());
        require(result != null && result.getScenario() != null
                        && Objects.equals(query.getFactoryCode().trim(), result.getScenario().getFactoryCode()),
                "排产情景不属于Agent运行工厂");
        return evidence(result);
    }

    @Override
    public PlanSchedulingAgentDTO.EffectEvidence effect(PlanSchedulingAgentDTO.ScenarioQuery query) {
        return effect(query(query));
    }

    @Override
    public PlanSchedulingAgentDTO.DecisionContext decisionContext(PlanSchedulingAgentDTO.ScenarioQuery query) {
        PlanSchedulingAgentDTO.ScenarioEvidence scenarioEvidence = query(query);
        PlanSchedulingAgentDTO.EffectEvidence effectEvidence = effect(scenarioEvidence);
        List<String> gaps = new ArrayList<>();
        Map<String, Map<String, String>> commonMetrics = commonMetrics(scenarioEvidence, gaps);
        PlnSchedulingScenarioEntity scenario = scenarioEvidence.getScenario().getScenario();
        PlanSchedulingAgentDTO.PlanningAuthorityEvidence planningAuthority =
                planningAuthority(scenario, gaps);
        if (Boolean.TRUE.equals(scenarioEvidence.getCandidates().stream()
                .anyMatch(item -> Boolean.TRUE.equals(item.getPlanImpactTruncated())
                        || Boolean.TRUE.equals(item.getResourceHotspotTruncated())))) {
            gaps.add("候选影响或资源热点明细已按安全上限截断，请下钻候选查看完整业务数据");
        }
        if (effectEvidence.getDataGaps() != null) gaps.addAll(effectEvidence.getDataGaps());
        List<String> refs = new ArrayList<>(List.of(
                "PLAN:pln_scheduling_scenario:" + scenario.getScenarioNo(),
                "PLAN:scenarioFingerprint:" + scenarioEvidence.getScenarioFingerprint(),
                "PLAN:sourceFingerprint:" + scenarioEvidence.getSourceFingerprint()));
        if (Boolean.TRUE.equals(effectEvidence.getAvailable())
                && StrUtil.isNotBlank(effectEvidence.getEffectFingerprint())) {
            refs.add("PLAN:effectFingerprint:" + effectEvidence.getEffectFingerprint());
        }
        if (planningAuthority.getPolicyId() != null) {
            refs.add("MDM:factoryPlanningPolicy:" + planningAuthority.getPolicyId()
                    + "@" + planningAuthority.getPolicyVersion());
        }
        if (StrUtil.isNotBlank(planningAuthority.getFormalPlanContractNo())) {
            refs.add("PLAN:formalPlanContract:" + planningAuthority.getFormalPlanContractNo()
                    + "@" + planningAuthority.getFormalPlanContractVersion());
        }
        return new PlanSchedulingAgentDTO.DecisionContext()
                .setContractVersion("PLAN_SCHEDULING_DECISION_CONTEXT_V2")
                .setScenarioEvidence(scenarioEvidence)
                .setEffectEvidence(effectEvidence)
                .setPlanningAuthority(planningAuthority)
                .setDecisionState(decisionState(scenario.getScenarioStatus(), scenarioEvidence))
                .setCommonMetricCodes(commonMetrics.isEmpty() ? List.of() :
                        commonMetrics.values().iterator().next().keySet().stream().toList())
                .setCandidateCommonMetrics(commonMetrics)
                .setComparable(!commonMetrics.isEmpty()
                        && commonMetrics.size() == scenarioEvidence.getCandidates().size())
                .setDataGaps(gaps.stream().filter(StrUtil::isNotBlank).distinct().limit(40).toList())
                .setEvidenceRefs(refs.stream().filter(StrUtil::isNotBlank).distinct().toList())
                .setCapturedTime(LocalDateTime.now());
    }

    /** 历史情景不猜测正式性；新版案件必须同时匹配当前策略版本与合同指纹。 */
    private PlanSchedulingAgentDTO.PlanningAuthorityEvidence planningAuthority(
            PlnSchedulingScenarioEntity scenario, List<String> gaps) {
        MdmFactoryPlanningPolicyDTO current = planningAuthorityService.requireCurrentPolicy(
                scenario.getFactoryCode());
        PlanSchedulingAgentDTO.PlanningAuthorityEvidence result =
                new PlanSchedulingAgentDTO.PlanningAuthorityEvidence()
                        .setPolicyId(current.getId()).setPolicyVersion(current.getVersionNo())
                        .setPolicyFingerprint(current.getContentFingerprint())
                        .setMrpAuthority(current.getMrpAuthority())
                        .setProductionOrderSyncMode(current.getProductionOrderSyncMode())
                        .setSubstituteGovernanceMode(current.getSubstituteGovernanceMode())
                        .setObservedAt(LocalDateTime.now());
        PlnApsCaseEntity apsCase = apsCaseMapper.selectOne(
                new LambdaQueryWrapper<PlnApsCaseEntity>()
                        .eq(PlnApsCaseEntity::getFactoryCode, scenario.getFactoryCode())
                        .eq(PlnApsCaseEntity::getScenarioId, scenario.getId())
                        .orderByDesc(PlnApsCaseEntity::getSourceVersion)
                        .orderByDesc(PlnApsCaseEntity::getId).last("LIMIT 1"));
        if (apsCase == null) {
            gaps.add("历史排产情景未冻结计划权威和正式合同，当前正式性不可判定");
            return result.setEvidenceStatus("HISTORICAL_UNDETERMINED");
        }
        boolean currentPolicy = Objects.equals(String.valueOf(apsCase.getPlanningPolicyId()), current.getId())
                && Objects.equals(apsCase.getPlanningPolicyVersion(), current.getVersionNo())
                && Objects.equals(apsCase.getPlanningPolicyFingerprint(), current.getContentFingerprint());
        if (!currentPolicy) {
            gaps.add("排产案件冻结的计划权威已变化，旧候选只能用于历史解释，不能继续正式提交");
            return result.setFormal(false).setSimulation(true).setEvidenceStatus("STALE_POLICY")
                    .setFormalPlanContractNo(apsCase.getFormalPlanContractNo())
                    .setFormalPlanContractVersion(apsCase.getFormalPlanContractVersion())
                    .setFormalPlanContractFingerprint(apsCase.getFormalPlanContractFingerprint());
        }
        boolean hasContract = StrUtil.isNotBlank(apsCase.getFormalPlanContractNo())
                && apsCase.getFormalPlanContractVersion() != null
                && StrUtil.isNotBlank(apsCase.getFormalPlanContractFingerprint());
        if (!hasContract) {
            gaps.add("排产案件尚未绑定已核验正式计划合同，只允许仿真、解释和变更建议");
            return result.setFormal(false).setSimulation(true).setEvidenceStatus("CONTRACT_MISSING");
        }
        try {
            PlnFormalPlanContractDTO contract = formalPlanContractService.getVerifiedContract(
                    apsCase.getFormalPlanContractNo(), apsCase.getFormalPlanContractVersion());
            require(Objects.equals(contract.getSourceContentFingerprint(),
                            apsCase.getFormalPlanContractFingerprint()),
                    "排产案件冻结的正式计划合同指纹与当前合同不一致");
        } catch (CheckedException exception) {
            gaps.add("排产案件原正式计划合同已失效或指纹变化，旧候选只能用于历史解释与仿真："
                    + exception.getMessage());
            return result.setFormal(false).setSimulation(true).setEvidenceStatus("CONTRACT_MISSING")
                    .setFormalPlanContractNo(apsCase.getFormalPlanContractNo())
                    .setFormalPlanContractVersion(apsCase.getFormalPlanContractVersion())
                    .setFormalPlanContractFingerprint(apsCase.getFormalPlanContractFingerprint());
        }
        return result.setFormal(true).setSimulation(false).setEvidenceStatus("CURRENT")
                .setFormalPlanContractNo(apsCase.getFormalPlanContractNo())
                .setFormalPlanContractVersion(apsCase.getFormalPlanContractVersion())
                .setFormalPlanContractFingerprint(apsCase.getFormalPlanContractFingerprint());
    }

    private PlanSchedulingAgentDTO.EffectEvidence effect(
            PlanSchedulingAgentDTO.ScenarioEvidence scenarioEvidence) {
        PlnSchedulingScenarioEntity scenario = scenarioEvidence.getScenario().getScenario();
        PlanSchedulingAgentDTO.EffectEvidence result = new PlanSchedulingAgentDTO.EffectEvidence()
                .setScenarioId(scenario.getId())
                .setScenarioNo(scenario.getScenarioNo())
                .setScenarioStatus(scenario.getScenarioStatus())
                .setCapturedTime(LocalDateTime.now());
        if (!List.of("PUBLISHED", "PARTIAL").contains(scenario.getScenarioStatus())) {
            return result.setAvailable(false)
                    .setSummary("当前情景尚未正式发布，不能评价MES执行效果")
                    .setNextAction("完成A2候选选择和异岗A3正式锁定后，再采集执行效果");
        }
        PlnSchedulingEffectReviewDTO.Detail detail = effectReviewService.detailByScenarioId(scenario.getId());
        if (detail == null || detail.getReview() == null) {
            return result.setAvailable(false)
                    .setSummary("排产已发布，效果回评任务尚未完成首次MES事实采集")
                    .setNextAction("等待效果回评调度任务采集，或在PLAN效果回评页面执行人工采集");
        }
        var review = detail.getReview();
        int toleranceMinutes = currentToleranceMinutes(detail);
        LocalDateTime observedAt = result.getCapturedTime();
        List<PlnSchedulingEffectReviewDTO.OperationEvidence> operations = currentOperationEvidence(detail);
        List<PlnSchedulingEffectReviewDTO.OperationEvidence> deviations = operations.stream()
                .filter(item -> isExecutionDeviation(item, toleranceMinutes, observedAt))
                .sorted(Comparator
                        .comparing((PlnSchedulingEffectReviewDTO.OperationEvidence item) ->
                                Boolean.TRUE.equals(item.getMatched()) ? 1 : 0)
                        .thenComparing(item -> maximumAbsoluteDeviation(item, observedAt), Comparator.reverseOrder())
                        .thenComparing(item -> StrUtil.blankToDefault(item.getIssueNo(), ""))
                        .thenComparing(item -> Objects.requireNonNullElse(item.getOperationSeq(), Integer.MAX_VALUE)))
                .toList();
        List<PlanSchedulingAgentDTO.DeviationDisposition> dispositions = deviations.stream()
                .map(item -> disposition(item, toleranceMinutes, observedAt))
                .toList();
        List<String> dataGaps = operations.stream()
                .map(PlnSchedulingEffectReviewDTO.OperationEvidence::getGapReason)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .limit(MAX_EFFECT_DATA_GAPS)
                .toList();
        String summary = "回评状态" + review.getReviewStatus()
                + "；完成率" + percent(review.getCompletionRate())
                + "；排程遵从率" + percent(review.getScheduleAdherenceRate())
                + "；逾期未开工" + countDisposition(dispositions, "LOCAL_REPLAN_CANDIDATE", "EXECUTION_BLOCKED")
                + "；可重排评估" + countDisposition(dispositions, "LOCAL_REPLAN_CANDIDATE")
                + (review.getActualEffectScore() == null ? "" : "；实际效果得分" + review.getActualEffectScore());
        String nextAction = switch (StrUtil.blankToDefault(review.getReviewStatus(), "UNKNOWN")) {
            case "COMPLETED" -> "复核最终效果与偏差原因，并将结论用于下一轮候选策略调优";
            case "SOURCE_UNAVAILABLE", "DATA_GAP" -> "处理MES执行事实或工序映射缺口后重新采集";
            case "WAITING_DISPATCH" -> "确认已发布计划是否已下达到MES";
            case "WAITING_EXECUTION" -> "等待现场开始报工后重新采集";
            default -> dispositions.stream().anyMatch(item -> Boolean.TRUE.equals(item.getReplanEligible()))
                    ? "对可移动的逾期未开工工序发起确定性局部重排评估；已执行工序只做偏差复盘"
                    : "持续滚动采集MES执行事实；先修复数据缺口或处理已执行工序的偏差根因";
        };
        result.setAvailable(true)
                .setToleranceMinutes(toleranceMinutes)
                .setPlannedOperationCount(review.getPlannedOperationCount())
                .setMatchedOperationCount(review.getMatchedOperationCount())
                .setUnmatchedOperationCount(Math.max(0,
                        Objects.requireNonNullElse(review.getPlannedOperationCount(), 0)
                                - Objects.requireNonNullElse(review.getMatchedOperationCount(), 0)))
                .setStartDeviationOperationCount((int) operations.stream()
                        .filter(item -> Boolean.FALSE.equals(item.getStartAdherent())).count())
                .setFinishDeviationOperationCount((int) operations.stream()
                        .filter(item -> Boolean.FALSE.equals(item.getFinishAdherent())).count())
                .setPendingStartOverdueOperationCount(countDisposition(
                        dispositions, "LOCAL_REPLAN_CANDIDATE", "EXECUTION_BLOCKED"))
                .setReplanCandidateOperationCount(countDisposition(dispositions, "LOCAL_REPLAN_CANDIDATE"))
                .setExecutionLockedDeviationCount(countDisposition(dispositions, "EXECUTION_LOCKED_REVIEW"))
                .setDataRepairRequiredCount(countDisposition(dispositions, "DATA_REPAIR_REQUIRED"))
                .setPriorityDeviations(deviations.stream().limit(MAX_EFFECT_DEVIATIONS).toList())
                .setDeviationTruncated(deviations.size() > MAX_EFFECT_DEVIATIONS)
                .setDataGaps(dataGaps)
                .setDeviationDispositions(dispositions.stream().limit(MAX_EFFECT_DEVIATIONS).toList())
                .setDetail(detail).setSummary(summary).setNextAction(nextAction);
        return result.setEffectFingerprint(effectFingerprint(result));
    }

    private Map<String, Map<String, String>> commonMetrics(
            PlanSchedulingAgentDTO.ScenarioEvidence evidence, List<String> gaps) {
        List<PlanSchedulingAgentDTO.CandidateEvidence> candidates = Objects.requireNonNullElse(
                evidence.getCandidates(), List.of());
        if (candidates.isEmpty()) {
            gaps.add("当前情景没有候选，无法形成共同指标分母");
            return Map.of();
        }
        List<Map<String, String>> values = new ArrayList<>();
        LinkedHashSet<String> common = null;
        for (PlanSchedulingAgentDTO.CandidateEvidence candidate : candidates) {
            try {
                JsonNode metrics = objectMapper.readTree(candidate.getMetricsSnapshot());
                if (!metrics.isObject()) throw new IllegalArgumentException("metrics is not object");
                LinkedHashMap<String, String> row = new LinkedHashMap<>();
                metrics.fields().forEachRemaining(entry -> {
                    JsonNode value = entry.getValue();
                    if (value.isValueNode()) row.put(entry.getKey(), value.asText());
                });
                values.add(row);
                if (common == null) common = new LinkedHashSet<>(row.keySet());
                else common.retainAll(row.keySet());
            } catch (Exception exception) {
                gaps.add("候选" + candidate.getCandidateName() + "指标快照无法解析，不能纳入共同分母");
                return Map.of();
            }
        }
        if (common == null || common.isEmpty()) {
            gaps.add("候选之间没有共同指标，禁止直接比较综合百分比");
            return Map.of();
        }
        LinkedHashMap<String, Map<String, String>> result = new LinkedHashMap<>();
        for (int index = 0; index < candidates.size(); index++) {
            LinkedHashMap<String, String> row = new LinkedHashMap<>();
            for (String key : common) row.put(key, values.get(index).get(key));
            result.put(String.valueOf(candidates.get(index).getTaskId()), row);
        }
        return result;
    }

    private String decisionState(String status, PlanSchedulingAgentDTO.ScenarioEvidence evidence) {
        if (evidence.getCandidates() == null || evidence.getCandidates().isEmpty()
                || Objects.requireNonNullElse(evidence.getFeasibleCandidateCount(), 0) == 0) return "BLOCKED";
        return switch (StrUtil.blankToDefault(status, "UNKNOWN")) {
            case "EVALUATING", "RUNNING" -> "EVALUATING";
            case "EVALUATED" -> "WAITING_SELECTION";
            case "SELECTED" -> "WAITING_APPROVAL";
            case "PUBLISHED", "PARTIAL" -> "PUBLISHED_OBSERVING";
            case "FAILED" -> "FAILED";
            default -> "EVALUATING";
        };
    }

    @Override
    public PlanProductionScheduleCommitmentDTO.Evidence productionCommitment(
            PlanProductionScheduleCommitmentDTO.Query query) {
        require(query != null && StrUtil.isNotBlank(query.getFactoryCode())
                && StrUtil.isNotBlank(query.getTaskCode()), "生产排程承诺查询必须指定工厂和MES工单号");
        String factoryCode = query.getFactoryCode().trim();
        String taskCode = query.getTaskCode().trim();
        requireFactory(factoryCode);
        R<MesPlanSourceMappingDTO> response = remoteMesTaskService.planSourceMapping(
                taskCode, null, factoryCode);
        require(response != null && response.isOk() && response.getData() != null,
                "MES未返回工单到PLAN来源计划的权威映射");
        MesPlanSourceMappingDTO mapping = response.getData();
        require(Objects.equals(factoryCode, mapping.getFactoryCode()), "MES来源映射工厂与Agent运行范围不一致");

        List<String> gaps = new ArrayList<>();
        if (!Boolean.TRUE.equals(mapping.getMappingComplete())) {
            gaps.add("MES来源映射不完整：" + StrUtil.blankToDefault(
                    mapping.getMappingMessage(), mapping.getMappingStatus()));
        }
        List<MesPlanSourceMappingDTO.Allocation> allocations = Objects.requireNonNullElse(
                mapping.getAllocations(), List.of());
        Map<String, List<MesPlanSourceMappingDTO.Allocation>> byIssue = allocations.stream()
                .filter(item -> item.getIssueId() != null || StrUtil.isNotBlank(item.getIssueNo()))
                .collect(Collectors.groupingBy(item -> item.getIssueId() == null
                                ? "NO:" + item.getIssueNo() : "ID:" + item.getIssueId(),
                        LinkedHashMap::new, Collectors.toList()));
        if (byIssue.isEmpty()) {
            gaps.add("MES工单没有可用于定位PLAN正式排程的来源计划分配");
        }

        List<PlanProductionScheduleCommitmentDTO.PlanCommitment> plans = byIssue.values().stream()
                .limit(MAX_COMMITMENT_PLANS)
                .map(items -> commitment(factoryCode, items, gaps))
                .toList();
        List<String> refs = new ArrayList<>();
        refs.add("MES:mes_task:" + taskCode);
        plans.forEach(item -> {
            if (item.getIssueId() != null) refs.add("PLAN:pln_issue:" + item.getIssueId());
            if (item.getLockTaskId() != null) refs.add("PLAN:pln_scheduling_task:" + item.getLockTaskId());
            if (item.getScenarioId() != null) refs.add("PLAN:pln_scheduling_scenario:" + item.getScenarioId());
        });
        return new PlanProductionScheduleCommitmentDTO.Evidence()
                .setToolCode(PRODUCTION_COMMITMENT_TOOL)
                .setFactoryCode(factoryCode)
                .setTaskCode(taskCode)
                .setMesMappingStatus(mapping.getMappingStatus())
                .setMesMappingComplete(mapping.getMappingComplete())
                .setMesMappingMessage(mapping.getMappingMessage())
                .setMesMappingFingerprint(mapping.getMappingFingerprint())
                .setObservedAt(LocalDateTime.now())
                .setSourcePlanCount(byIssue.size())
                .setCommittedPlanCount((int) plans.stream()
                        .filter(item -> Boolean.TRUE.equals(item.getCommitmentAvailable())).count())
                .setPlans(plans)
                .setDataGaps(gaps.stream().distinct().limit(30).toList())
                .setEvidenceRefs(refs.stream().distinct().toList())
                .setTruncated(byIssue.size() > MAX_COMMITMENT_PLANS);
    }

    private PlanProductionScheduleCommitmentDTO.PlanCommitment commitment(
            String factoryCode, List<MesPlanSourceMappingDTO.Allocation> allocations,
            List<String> evidenceGaps) {
        MesPlanSourceMappingDTO.Allocation source = allocations.get(0);
        PlnIssueEntity issue = source.getIssueId() == null ? issueMapper.selectOne(
                new LambdaQueryWrapper<PlnIssueEntity>()
                        .eq(PlnIssueEntity::getFactoryCode, factoryCode)
                        .eq(PlnIssueEntity::getIssueNo, source.getIssueNo()).last("LIMIT 1"))
                : issueMapper.selectById(source.getIssueId());
        PlanProductionScheduleCommitmentDTO.PlanCommitment result =
                new PlanProductionScheduleCommitmentDTO.PlanCommitment()
                        .setIssueId(source.getIssueId())
                        .setIssueNo(source.getIssueNo())
                        .setMesAllocatedQuantity(sumAllocation(allocations,
                                MesPlanSourceMappingDTO.Allocation::getAllocatedQuantity))
                        .setMesCompletedQuantity(sumAllocation(allocations,
                                MesPlanSourceMappingDTO.Allocation::getCompletedQuantity));
        if (issue == null || !Objects.equals(factoryCode, issue.getFactoryCode())) {
            String gap = "来源计划不存在或超出当前工厂：" + source.getIssueNo();
            evidenceGaps.add(gap);
            return result.setCommitmentAvailable(false).setLockStatus(false)
                    .setDataGaps(List.of(gap)).setOperations(List.of()).setOperationTruncated(false);
        }
        result.setIssueId(issue.getId()).setIssueNo(issue.getIssueNo())
                .setProductCode(issue.getProductCode()).setProductName(issue.getProductName())
                .setIssueQuantity(issue.getNum()).setDemandDate(issue.getDemandTime())
                .setIssueStatus(issue.getStatus()).setLockStatus(issue.getLockStatus())
                .setLockTaskId(issue.getLockTaskId());
        if (!Boolean.TRUE.equals(issue.getLockStatus()) || issue.getLockTaskId() == null) {
            String gap = "PLAN计划尚无当前锁定的正式工序排程：" + issue.getIssueNo();
            evidenceGaps.add(gap);
            return result.setCommitmentAvailable(false).setDataGaps(List.of(gap))
                    .setOperations(List.of()).setOperationTruncated(false);
        }

        PlnSchedulingTaskEntity task = schedulingTaskMapper.selectById(issue.getLockTaskId());
        PlnSchedulingScenarioEntity scenario = task == null || task.getScenarioId() == null
                ? null : scenarioMapper.selectById(task.getScenarioId());
        if (task == null || !Objects.equals(factoryCode, task.getFactoryCode())
                || !"official".equalsIgnoreCase(task.getMode())
                || !Set.of("SUCCESS", "PARTIAL").contains(task.getTaskStatus())
                || scenario != null && !Set.of("PUBLISHED", "PARTIAL").contains(scenario.getScenarioStatus())) {
            String gap = "PLAN计划锁定标识未指向当前工厂的有效正式排程任务：" + issue.getIssueNo();
            evidenceGaps.add(gap);
            return result.setCommitmentAvailable(false).setDataGaps(List.of(gap))
                    .setOperations(List.of()).setOperationTruncated(false);
        }
        List<PlnSchedulingOperationEntity> operations = schedulingOperationMapper.selectList(
                new LambdaQueryWrapper<PlnSchedulingOperationEntity>()
                        .eq(PlnSchedulingOperationEntity::getFactoryCode, factoryCode)
                        .eq(PlnSchedulingOperationEntity::getTaskId, issue.getLockTaskId())
                        .eq(PlnSchedulingOperationEntity::getIssueId, issue.getId())
                        .eq(PlnSchedulingOperationEntity::getLockStatus, true)
                        .orderByAsc(PlnSchedulingOperationEntity::getOperationSeq)
                        .orderByAsc(PlnSchedulingOperationEntity::getId));
        if (operations.isEmpty()) {
            String gap = "PLAN计划锁定标识与正式排程任务/工序不一致：" + issue.getIssueNo();
            evidenceGaps.add(gap);
            return result.setCommitmentAvailable(false).setDataGaps(List.of(gap))
                    .setOperations(List.of()).setOperationTruncated(false);
        }
        List<PlanProductionScheduleCommitmentDTO.OperationCommitment> operationViews = operations.stream()
                .limit(MAX_COMMITMENT_OPERATIONS).map(this::operationCommitment).toList();
        List<String> planGaps = new ArrayList<>();
        if (operations.stream().anyMatch(item -> Boolean.TRUE.equals(item.getHardBlocked()))) {
            planGaps.add("正式排程仍包含硬阻断工序，请核对发布或局部重排结果");
        }
        if (operations.size() > MAX_COMMITMENT_OPERATIONS) {
            planGaps.add("工序明细超过" + MAX_COMMITMENT_OPERATIONS + "条，返回结果已截断");
        }
        evidenceGaps.addAll(planGaps.stream().map(gap -> issue.getIssueNo() + "：" + gap).toList());
        result.setCommitmentAvailable(true)
                .setApsTaskNo(task.getTaskNo())
                .setScenarioId(scenario == null ? null : scenario.getId())
                .setScenarioNo(scenario == null ? null : scenario.getScenarioNo())
                .setScenarioStatus(scenario == null ? null : scenario.getScenarioStatus())
                .setCandidateCode(task.getCandidateCode()).setCandidateName(task.getCandidateName())
                .setScheduledStartTime(operations.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                        .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null))
                .setScheduledEndTime(operations.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                        .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null))
                .setOperationCount(operations.size())
                .setHardBlockedOperationCount((int) operations.stream()
                        .filter(item -> Boolean.TRUE.equals(item.getHardBlocked())).count())
                .setOperations(operationViews).setDataGaps(planGaps)
                .setOperationTruncated(operations.size() > MAX_COMMITMENT_OPERATIONS);
        Map<String, Object> fingerprintSource = new LinkedHashMap<>();
        fingerprintSource.put("sourceIssueId", source.getIssueId());
        fingerprintSource.put("sourceIssueNo", source.getIssueNo());
        fingerprintSource.put("lockTaskId", issue.getLockTaskId());
        fingerprintSource.put("taskStatus", task.getTaskStatus());
        fingerprintSource.put("scenarioStatus", scenario == null ? null : scenario.getScenarioStatus());
        fingerprintSource.put("operations", operationViews);
        result.setCommitmentFingerprint(hash(json(fingerprintSource)));
        return result;
    }

    private PlanProductionScheduleCommitmentDTO.OperationCommitment operationCommitment(
            PlnSchedulingOperationEntity item) {
        return new PlanProductionScheduleCommitmentDTO.OperationCommitment()
                .setOperationId(item.getId()).setOperationSeq(item.getOperationSeq())
                .setProcessCode(item.getProcessCode()).setProcessName(item.getProcessName())
                .setResourceType(item.getResourceType()).setResourceCode(item.getResourceCode())
                .setResourceName(item.getResourceName()).setDeviceCode(item.getDeviceCode())
                .setDeviceName(item.getDeviceName()).setEmployeeCode(item.getEmployeeCode())
                .setEmployeeName(item.getEmployeeName()).setToolingCodes(item.getToolingCodes())
                .setScheduledStartTime(item.getScheduledStartTime()).setScheduledEndTime(item.getScheduledEndTime())
                .setMaterialConstraintStatus(item.getMaterialConstraintStatus())
                .setHardBlocked(item.getHardBlocked()).setConstraintSummary(item.getConstraintSummary())
                .setLockStatus(item.getLockStatus());
    }

    private BigDecimal sumAllocation(List<MesPlanSourceMappingDTO.Allocation> allocations,
                                     java.util.function.Function<MesPlanSourceMappingDTO.Allocation, BigDecimal> mapper) {
        return allocations.stream().map(mapper).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 从当前不可变观察快照提取逐工序事实。历史观察仍原样保留在detail中，Agent只使用
     * currentObservationId对应的最新口径，避免把不同采集时点拼接成一份伪快照。
     */
    private List<PlnSchedulingEffectReviewDTO.OperationEvidence> currentOperationEvidence(
            PlnSchedulingEffectReviewDTO.Detail detail) {
        if (detail.getReview() == null || detail.getObservations() == null) {
            return List.of();
        }
        var current = detail.getObservations().stream()
                .filter(item -> Objects.equals(item.getId(), detail.getReview().getCurrentObservationId()))
                .findFirst()
                .orElse(detail.getObservations().isEmpty() ? null : detail.getObservations().get(0));
        if (current == null || StrUtil.isBlank(current.getActualSnapshot())) {
            return List.of();
        }
        try {
            JsonNode operations = objectMapper.readTree(current.getActualSnapshot()).path("operations");
            if (!operations.isArray()) {
                return List.of();
            }
            return objectMapper.readerForListOf(PlnSchedulingEffectReviewDTO.OperationEvidence.class)
                    .readValue(operations);
        } catch (Exception exception) {
            throw new CheckedException("排产执行效果逐工序证据解析失败", exception);
        }
    }

    private boolean isExecutionDeviation(PlnSchedulingEffectReviewDTO.OperationEvidence item,
                                         int toleranceMinutes, LocalDateTime observedAt) {
        return !Boolean.TRUE.equals(item.getMatched())
                || Boolean.FALSE.equals(item.getStartAdherent())
                || Boolean.FALSE.equals(item.getFinishAdherent())
                || pendingStartOverdueMinutes(item, toleranceMinutes, observedAt) > 0
                || StrUtil.isNotBlank(item.getGapReason());
    }

    private Long maximumAbsoluteDeviation(PlnSchedulingEffectReviewDTO.OperationEvidence item,
                                          LocalDateTime observedAt) {
        long start = item.getStartDeviationMinutes() == null ? 0 : Math.abs(item.getStartDeviationMinutes());
        long finish = item.getFinishDeviationMinutes() == null ? 0 : Math.abs(item.getFinishDeviationMinutes());
        long pending = item.getActualStartTime() == null && item.getPlannedStartTime() != null
                && observedAt.isAfter(item.getPlannedStartTime())
                ? Duration.between(item.getPlannedStartTime(), observedAt).toMinutes() : 0;
        return Math.max(Math.max(start, finish), pending);
    }

    private int currentToleranceMinutes(PlnSchedulingEffectReviewDTO.Detail detail) {
        if (detail == null || detail.getReview() == null || detail.getObservations() == null) {
            return 30;
        }
        var current = detail.getObservations().stream()
                .filter(item -> Objects.equals(item.getId(), detail.getReview().getCurrentObservationId()))
                .findFirst().orElse(detail.getObservations().isEmpty() ? null : detail.getObservations().get(0));
        if (current == null || current.getToleranceMinutes() == null) {
            return 30;
        }
        return Math.max(0, Math.min(current.getToleranceMinutes(), 1440));
    }

    private long pendingStartOverdueMinutes(PlnSchedulingEffectReviewDTO.OperationEvidence item,
                                            int toleranceMinutes, LocalDateTime observedAt) {
        if (!Boolean.TRUE.equals(item.getMatched()) || item.getActualStartTime() != null
                || item.getPlannedStartTime() == null || observedAt == null) {
            return 0;
        }
        long delay = Duration.between(item.getPlannedStartTime(), observedAt).toMinutes();
        return delay > toleranceMinutes ? delay : 0;
    }

    private PlanSchedulingAgentDTO.DeviationDisposition disposition(
            PlnSchedulingEffectReviewDTO.OperationEvidence item,
            int toleranceMinutes, LocalDateTime observedAt) {
        long pendingDelay = pendingStartOverdueMinutes(item, toleranceMinutes, observedAt);
        String type;
        String severity;
        String reason;
        String workflow;
        boolean eligible = false;
        if (!Boolean.TRUE.equals(item.getMatched()) || StrUtil.isNotBlank(item.getGapReason())) {
            type = "DATA_REPAIR_REQUIRED";
            severity = "CRITICAL";
            reason = StrUtil.blankToDefault(item.getGapReason(), "PLAN与MES工序未形成精确映射");
            workflow = "先修复MES任务、工序编码或来源映射并重新采集，禁止基于缺证工序生成重排";
        } else if (item.getActualStartTime() != null || item.getActualFinishTime() != null) {
            type = "EXECUTION_LOCKED_REVIEW";
            severity = Boolean.FALSE.equals(item.getFinishAdherent()) ? "CRITICAL" : "WARNING";
            reason = item.getActualFinishTime() != null
                    ? "工序已经完工，历史执行事实不可通过重排改写"
                    : "工序已经开工，当前工序不可通过重排改写";
            workflow = "进入偏差根因复盘；只允许评估尚未开工的后继工序";
        } else if (pendingDelay > 0 && Boolean.TRUE.equals(item.getMovable())) {
            type = "LOCAL_REPLAN_CANDIDATE";
            severity = "CRITICAL";
            eligible = true;
            reason = "工序逾期未开工" + pendingDelay + "分钟，且MES确认仍可移动";
            workflow = "进入确定性局部重排评估，冻结MES执行指纹后生成多候选并走A2/A3";
        } else if (pendingDelay > 0) {
            type = "EXECUTION_BLOCKED";
            severity = "CRITICAL";
            reason = "工序逾期未开工但MES禁止移动："
                    + StrUtil.blankToDefault(item.getMovementBlockedReason(), "未返回阻断原因");
            workflow = "先处理MES派工、冻结区或现场执行阻断，再重新采集评估";
        } else {
            type = "OBSERVE";
            severity = "INFO";
            reason = "当前偏差只需继续观察";
            workflow = "保持滚动效果回评";
        }
        List<String> refs = new ArrayList<>();
        if (item.getIssueId() != null) refs.add("PLAN:pln_issue:" + item.getIssueId());
        if (StrUtil.isNotBlank(item.getIssueNo())) refs.add("PLAN:pln_issue_no:" + item.getIssueNo());
        if (StrUtil.isNotBlank(item.getMesTaskCode())) refs.add("MES:mes_task:" + item.getMesTaskCode());
        if (StrUtil.isNotBlank(item.getProcessCode())) refs.add("MES:process:" + item.getProcessCode());
        return new PlanSchedulingAgentDTO.DeviationDisposition()
                .setIssueId(item.getIssueId()).setIssueNo(item.getIssueNo())
                .setMesTaskCode(item.getMesTaskCode()).setProcessCode(item.getProcessCode())
                .setProcessName(item.getProcessName()).setOperationSeq(item.getOperationSeq())
                .setMesStatus(item.getMesStatus()).setDisposition(type).setSeverity(severity)
                .setReplanEligible(eligible).setPendingStartOverdueMinutes(pendingDelay > 0 ? pendingDelay : null)
                .setStartDeviationMinutes(item.getStartDeviationMinutes())
                .setFinishDeviationMinutes(item.getFinishDeviationMinutes())
                .setReason(reason).setRecommendedWorkflow(workflow).setEvidenceRefs(refs);
    }

    private int countDisposition(List<PlanSchedulingAgentDTO.DeviationDisposition> dispositions,
                                 String... types) {
        Set<String> accepted = Set.of(types);
        return (int) dispositions.stream().filter(item -> accepted.contains(item.getDisposition())).count();
    }

    private String effectFingerprint(PlanSchedulingAgentDTO.EffectEvidence evidence) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("scenarioId", evidence.getScenarioId());
        facts.put("scenarioNo", evidence.getScenarioNo());
        facts.put("scenarioStatus", evidence.getScenarioStatus());
        facts.put("toleranceMinutes", evidence.getToleranceMinutes());
        facts.put("plannedOperationCount", evidence.getPlannedOperationCount());
        facts.put("matchedOperationCount", evidence.getMatchedOperationCount());
        facts.put("unmatchedOperationCount", evidence.getUnmatchedOperationCount());
        facts.put("startDeviationOperationCount", evidence.getStartDeviationOperationCount());
        facts.put("finishDeviationOperationCount", evidence.getFinishDeviationOperationCount());
        facts.put("pendingStartOverdueOperationCount", evidence.getPendingStartOverdueOperationCount());
        List<PlanSchedulingAgentDTO.DeviationDisposition> dispositions =
                evidence.getDeviationDispositions() == null ? List.of() : evidence.getDeviationDispositions();
        facts.put("dispositions", dispositions.stream().map(item -> {
                    Map<String, Object> disposition = new LinkedHashMap<>();
                    disposition.put("issueId", item.getIssueId());
                    disposition.put("issueNo", item.getIssueNo());
                    disposition.put("processCode", item.getProcessCode());
                    disposition.put("mesStatus", item.getMesStatus());
                    disposition.put("disposition", item.getDisposition());
                    disposition.put("replanEligible", item.getReplanEligible());
                    disposition.put("startDeviationMinutes", item.getStartDeviationMinutes());
                    disposition.put("finishDeviationMinutes", item.getFinishDeviationMinutes());
                    return disposition;
                }).toList());
        facts.put("dataGaps", evidence.getDataGaps());
        return hash(json(facts));
    }

    @Override
    public PlanSchedulingAgentDTO.ObjectiveTemplateCatalog objectiveTemplates(
            PlanSchedulingAgentDTO.ObjectiveTemplateQuery query) {
        require(query != null && StrUtil.isNotBlank(query.getFactoryCode()), "行业目标模板查询必须指定工厂");
        String factoryCode = query.getFactoryCode().trim();
        requireFactory(factoryCode);
        List<PlnObjectiveTemplate> templates = objectiveTemplateMapper.selectList(
                new LambdaQueryWrapper<PlnObjectiveTemplate>()
                        .eq(PlnObjectiveTemplate::getFactoryCode, factoryCode)
                        .eq(PlnObjectiveTemplate::getStatus, "ACTIVE")
                        .eq(StrUtil.isNotBlank(query.getIndustryCode()), PlnObjectiveTemplate::getIndustryCode,
                                upper(query.getIndustryCode()))
                        .eq(StrUtil.isNotBlank(query.getTemplateCode()), PlnObjectiveTemplate::getTemplateCode,
                                upper(query.getTemplateCode()))
                        .orderByAsc(PlnObjectiveTemplate::getIndustryCode)
                        .orderByAsc(PlnObjectiveTemplate::getTemplateCode)
                        .orderByDesc(PlnObjectiveTemplate::getVersionNo)
                        .last("LIMIT 100"));
        return new PlanSchedulingAgentDTO.ObjectiveTemplateCatalog()
                .setFactoryCode(factoryCode)
                .setTemplates(templates.stream().map(this::objectiveTemplateOption).toList())
                .setCapturedTime(LocalDateTime.now());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanSchedulingAgentDTO.ScenarioEvidence select(PlanSchedulingAgentDTO.SelectCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getFactoryCode())
                        && StrUtil.isNotBlank(command.getScenarioNo()) && StrUtil.isNotBlank(command.getRequestId())
                        && command.getCandidateTaskId() != null && StrUtil.isNotBlank(command.getExpectedScenarioFingerprint())
                        && StrUtil.isNotBlank(command.getExpectedCandidateFingerprint()) && StrUtil.isNotBlank(command.getReason()),
                "Agent候选选择缺少情景、候选、来源证据或原因");
        PlanSchedulingAgentDTO.ScenarioEvidence current = query(queryOf(command.getFactoryCode(), command.getScenarioNo()));
        require(Objects.equals(current.getScenarioFingerprint(), command.getExpectedScenarioFingerprint()),
                "情景或候选指标已变化，旧选择请求失效");
        PlanSchedulingAgentDTO.CandidateEvidence candidate = current.getCandidates().stream()
                .filter(item -> Objects.equals(item.getTaskId(), command.getCandidateTaskId())).findFirst()
                .orElseThrow(() -> new CheckedException("所选候选不属于当前情景"));
        require(Boolean.TRUE.equals(candidate.getFeasible()), "存在硬阻断的候选禁止选择");
        require(Objects.equals(candidate.getCandidateFingerprint(), command.getExpectedCandidateFingerprint()),
                "候选工序、资源或指标证据已变化，旧选择请求失效");
        PlnSchedulingScenarioSelectDTO select = new PlnSchedulingScenarioSelectDTO();
        select.setRequestId(command.getRequestId().trim());
        select.setCandidateTaskId(command.getCandidateTaskId());
        select.setExpectedSourceFingerprint(current.getSourceFingerprint());
        select.setReason(command.getReason().trim());
        return evidence(schedulingEngineService.selectScenarioCandidate(command.getScenarioNo().trim(), select));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlanSchedulingAgentDTO.PublishReceipt publish(PlanSchedulingAgentDTO.PublishCommand command) {
        validatePublish(command);
        String requestId = command.getRequestId().trim();
        String requestFingerprint = hash(json(publishFingerprintFields(command)));
        scenarioMapper.lock("APS_AGENT_PUBLISH:" + requestId);
        PlnSchedulingScenarioEntity duplicate = scenarioMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                        .eq(PlnSchedulingScenarioEntity::getPublishRequestId, requestId));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getScenarioNo(), command.getScenarioNo().trim())
                            && Objects.equals(duplicate.getPublishRequestFingerprint(), requestFingerprint),
                    "正式锁定幂等号已被不同情景或载荷使用");
            return receipt(duplicate);
        }
        scenarioMapper.lock("APS_AGENT_SCENARIO:" + command.getScenarioNo().trim());
        PlnSchedulingScenarioEntity scenario = scenarioMapper.selectOne(
                new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                        .eq(PlnSchedulingScenarioEntity::getScenarioNo, command.getScenarioNo().trim()).last("FOR UPDATE"));
        require(scenario != null && Objects.equals(scenario.getFactoryCode(), command.getFactoryCode().trim()),
                "待发布情景不存在或跨工厂");
        require("SELECTED".equals(scenario.getScenarioStatus()), "只有已完成候选选择的情景可以正式锁定");
        require(Objects.equals(scenario.getSelectedTaskId(), command.getSelectedTaskId()), "A3命令中的候选不是情景已选方案");
        require(Objects.equals(scenario.getSelectionFingerprint(), command.getExpectedSelectionFingerprint()),
                "候选选择事实已变化，旧A3审批失效");
        PlanSchedulingAgentDTO.ScenarioEvidence current = evidence(schedulingEngineService.getScenario(scenario.getScenarioNo()));
        require(Objects.equals(current.getScenarioFingerprint(), command.getExpectedScenarioFingerprint()),
                "发布前情景、指标或工序证据已变化，旧A3审批失效");
        require(Boolean.TRUE.equals(schedulingEngineService.confirmOfficialTaskLock(command.getSelectedTaskId())),
                "APS未确认正式锁定成功");
        scenario = scenarioMapper.selectById(scenario.getId());
        require(scenario != null && List.of("PUBLISHED", "PARTIAL").contains(scenario.getScenarioStatus()),
                "APS正式锁定完成但情景状态异常");
        scenario.setPublishRequestId(requestId);
        scenario.setPublishRequestFingerprint(requestFingerprint);
        scenario.setAgentApprovalId(command.getAgentApprovalId());
        scenario.setAgentApprovalNo(command.getAgentApprovalNo().trim());
        scenario.setPublishedBy(command.getApproverCode().trim());
        scenario.setPublishedTime(LocalDateTime.now());
        require(scenarioMapper.updateById(scenario) == 1, "Agent正式锁定审计保存失败");
        return receipt(scenario);
    }

    private PlanSchedulingAgentDTO.ScenarioEvidence evidence(PlnSchedulingScenarioDTO source) {
        PlnSchedulingScenarioEntity scenario = source.getScenario();
        List<PlanSchedulingAgentDTO.CandidateEvidence> candidates = new ArrayList<>();
        List<PlnSchedulingTaskEntity> sourceCandidates = source.getCandidates() == null
                ? List.of() : source.getCandidates();
        for (PlnSchedulingTaskEntity task : sourceCandidates) {
            List<PlnSchedulingTaskDetailEntity> details = taskDetailMapper.selectList(
                    new LambdaQueryWrapper<PlnSchedulingTaskDetailEntity>()
                            .eq(PlnSchedulingTaskDetailEntity::getTaskId, task.getId())
                            .orderByAsc(PlnSchedulingTaskDetailEntity::getIssueId));
            List<PlnSchedulingOperationEntity> operations = operationSchedulingService
                    .listTaskOperations(task.getId(), null, null).stream()
                    .sorted(Comparator.comparing(PlnSchedulingOperationEntity::getIssueId,
                                    Comparator.nullsLast(Long::compareTo))
                            .thenComparing(PlnSchedulingOperationEntity::getOperationSeq,
                                    Comparator.nullsLast(Integer::compareTo))
                            .thenComparing(PlnSchedulingOperationEntity::getId,
                                    Comparator.nullsLast(Long::compareTo))).toList();
            List<PlanSchedulingAgentDTO.PlanImpact> planImpacts = planImpacts(details, operations);
            List<PlanSchedulingAgentDTO.ResourceHotspot> resourceHotspots = resourceHotspots(
                    task.getCandidateMetricsSnapshot());
            String candidateFingerprint = candidateFingerprint(scenario, task, details, operations);
            candidates.add(new PlanSchedulingAgentDTO.CandidateEvidence()
                    .setTaskId(task.getId()).setCandidateCode(task.getCandidateCode())
                    .setParentCandidateTaskId(task.getParentCandidateTaskId())
                    .setDerivationReason(task.getDerivationReason())
                    .setDerivationDiffSnapshot(task.getDerivationDiffSnapshot())
                    .setCandidateName(task.getCandidateName()).setSequenceStrategy(task.getSequenceStrategy())
                    .setRank(task.getCandidateRank()).setScore(task.getCandidateScore())
                    .setFeasible(task.getCandidateFeasible()).setSelected(task.getSelectedCandidate())
                    .setMetricsSnapshot(task.getCandidateMetricsSnapshot())
                    .setObjectiveWeightsSnapshot(task.getObjectiveWeightsSnapshot())
                    .setCandidateFingerprint(candidateFingerprint).setTradeoffSummary(tradeoff(task))
                    .setImpactedPlanCount(details.size()).setPriorityPlanImpacts(planImpacts)
                    .setPlanImpactTruncated(details.size() > planImpacts.size())
                    .setResourceHotspots(resourceHotspots)
                    .setResourceHotspotTruncated(resourceDayCount(task.getCandidateMetricsSnapshot())
                            > resourceHotspots.size()));
        }
        long feasible = candidates.stream().filter(item -> Boolean.TRUE.equals(item.getFeasible())).count();
        String scenarioFingerprint = scenarioFingerprint(scenario, candidates);
        PlanSchedulingAgentDTO.CandidateEvidence recommended = candidates.stream()
                .filter(item -> Boolean.TRUE.equals(item.getFeasible()))
                .min(Comparator.comparing(item -> Objects.requireNonNullElse(item.getRank(), Integer.MAX_VALUE)))
                .orElse(null);
        String recommendation = recommended == null
                ? "全部候选存在硬阻断，禁止选择和正式锁定；应先处理数据或资源约束后重新求解"
                : "推荐第" + recommended.getRank() + "名【" + recommended.getCandidateName() + "】，综合分"
                + recommended.getScore() + "；该推荐只用于比较，计划员仍需结合逐工序影响选择";
        return new PlanSchedulingAgentDTO.ScenarioEvidence().setScenario(source)
                .setScenarioFingerprint(scenarioFingerprint).setSourceFingerprint(scenario.getSourceFingerprint())
                .setRuleVersionRef(scenario.getRuleCode() + "@" + scenario.getRuleVersion())
                .setObjectiveTemplateVersionRef(templateVersionRef(templateFromScenario(scenario)))
                .setRecommendationSummary(recommendation)
                .setDecisionBrief(decisionBrief(scenario, candidates, recommended))
                .setFeasibleCandidateCount((int) feasible)
                .setHardBlockedCandidateCount(candidates.size() - (int) feasible)
                .setCapturedTime(LocalDateTime.now()).setCandidates(candidates);
    }

    private PlanSchedulingAgentDTO.DecisionBrief decisionBrief(
            PlnSchedulingScenarioEntity scenario,
            List<PlanSchedulingAgentDTO.CandidateEvidence> candidates,
            PlanSchedulingAgentDTO.CandidateEvidence recommended) {
        List<MetricView> feasible = candidates.stream()
                .filter(item -> Boolean.TRUE.equals(item.getFeasible()))
                .map(this::metricView)
                .filter(Objects::nonNull)
                .toList();
        if (recommended == null) {
            return new PlanSchedulingAgentDTO.DecisionBrief()
                    .setConclusion("当前没有满足全部硬约束的候选，Agent拒绝推荐和发布")
                    .setGains(List.of())
                    .setTradeoffs(List.of())
                    .setRisks(List.of("全部" + candidates.size() + "个候选存在硬阻断，需先处理物料、工艺、日历或资源事实"))
                    .setNextStep("进入数据缺口责任工作台，修复权威事实后重新预检并求解");
        }
        MetricView selected = feasible.stream()
                .filter(item -> Objects.equals(item.candidate().getTaskId(), recommended.getTaskId()))
                .findFirst().orElse(null);
        List<String> gains = new ArrayList<>();
        List<String> tradeoffs = new ArrayList<>();
        if (selected != null) {
            compareLowerIsBetter(feasible, selected, "totalLateDays", "总延期", "天", gains, tradeoffs);
            compareLowerIsBetter(feasible, selected, "changeoverMinutes", "换型", "分钟", gains, tradeoffs);
            compareLowerIsBetter(feasible, selected, "makespanMinutes", "排程周期", "分钟", gains, tradeoffs);
            compareLowerIsBetter(feasible, selected, "waitingMinutes", "工序等待", "分钟", gains, tradeoffs);
            compareLowerIsBetter(feasible, selected, "loadImbalanceBasisPoints", "负荷不均衡", "基点", gains, tradeoffs);
            compareLowerIsBetter(feasible, selected, "wipExposureMinutes", "在制暴露", "分钟", gains, tradeoffs);
            compareLowerIsBetter(feasible, selected, "requiredOvertimeMinutes", "所需加班", "分钟", gains, tradeoffs);
            compareLowerIsBetter(feasible, selected, "disturbanceMinutes", "相对正式计划扰动", "分钟", gains, tradeoffs);
        }
        if (gains.isEmpty()) {
            gains.add("可行候选的主要指标没有形成显著差异，应下钻逐工序资源占用再决定");
        }
        if (tradeoffs.isEmpty()) {
            tradeoffs.add("推荐方案在已计算的八项目标上没有劣于其他可行候选的单项指标");
        }
        int blockedCandidateCount = (int) candidates.stream()
                .filter(item -> !Boolean.TRUE.equals(item.getFeasible())).count();
        List<String> risks = decisionRisks(selected, blockedCandidateCount);
        return new PlanSchedulingAgentDTO.DecisionBrief()
                .setRecommendedTaskId(recommended.getTaskId())
                .setRecommendedCandidateName(recommended.getCandidateName())
                .setConclusion("推荐【" + recommended.getCandidateName() + "】（第" + recommended.getRank()
                        + "名，综合分" + recommended.getScore() + "），推荐依据来自同一来源快照的八目标比较")
                .setGains(gains)
                .setTradeoffs(tradeoffs)
                .setRisks(risks)
                .setNextStep(nextStep(scenario.getScenarioStatus()));
    }

    private void compareLowerIsBetter(List<MetricView> feasible, MetricView selected, String key,
                                      String label, String unit, List<String> gains, List<String> tradeoffs) {
        long value = selected.value(key);
        long min = feasible.stream().mapToLong(item -> item.value(key)).min().orElse(value);
        long max = feasible.stream().mapToLong(item -> item.value(key)).max().orElse(value);
        if (max == min) return;
        if (value == min) {
            gains.add(label + value + unit + "，比该指标最高的可行候选减少" + (max - value) + unit);
        } else {
            tradeoffs.add(label + value + unit + "，比该指标最优候选增加" + (value - min) + unit);
        }
    }

    private List<String> decisionRisks(MetricView selected, int blockedCandidateCount) {
        List<String> risks = new ArrayList<>();
        if (selected == null) {
            risks.add("推荐候选的指标快照无法解析，禁止仅凭综合分发布");
            return risks;
        }
        JsonNode m = selected.metrics();
        if (m.path("lateCount").asLong() > 0) {
            risks.add("仍有" + m.path("lateCount").asLong() + "个计划延期，最大延期"
                    + m.path("maxLateDays").asLong() + "天");
        }
        if (m.path("overloadedResourceDays").asLong() > 0) {
            risks.add("存在" + m.path("overloadedResourceDays").asLong() + "个资源日超载，预计加班"
                    + m.path("requiredOvertimeMinutes").asLong() + "分钟");
        }
        if (m.path("capacityUnverifiedResourceDays").asLong() > 0) {
            risks.add("有" + m.path("capacityUnverifiedResourceDays").asLong()
                    + "个资源日容量未经确认，发布前需人工复核");
        }
        if (m.path("loadCoverageTruncated").asBoolean(false)) {
            risks.add("资源负荷评估超过92天窗口，远期负荷未完整覆盖");
        }
        if (m.path("fallbackResourceCount").asLong() > 0) {
            risks.add("有" + m.path("fallbackResourceCount").asLong()
                    + "道工序使用非工位级资源分配，需确认现场可执行性");
        }
        if (blockedCandidateCount > 0) {
            risks.add("另有" + blockedCandidateCount + "个候选存在硬阻断，不得切换发布");
        }
        if (risks.isEmpty()) {
            risks.add("当前候选未发现延期、超载、容量缺证或非工位级资源风险，仍需计划员确认逐工序影响");
        }
        return risks;
    }

    private String nextStep(String scenarioStatus) {
        return switch (StrUtil.blankToDefault(scenarioStatus, "UNKNOWN")) {
            case "EVALUATED" -> "计划员复核收益、代价和风险后执行A2候选选择";
            case "SELECTED" -> "候选已完成A2选择，提交非申请人A3审批后才能正式锁定";
            case "PUBLISHED", "PARTIAL" -> "已进入正式排程，持续关注MES执行偏差和效果回评";
            case "FAILED" -> "处理求解失败和数据缺口后重新生成候选";
            default -> "等待APS完成候选评估后再进行人工选择";
        };
    }

    private MetricView metricView(PlanSchedulingAgentDTO.CandidateEvidence candidate) {
        try {
            return new MetricView(candidate, objectMapper.readTree(candidate.getMetricsSnapshot()));
        } catch (Exception ex) {
            return null;
        }
    }

    private List<PlanSchedulingAgentDTO.PlanImpact> planImpacts(
            List<PlnSchedulingTaskDetailEntity> details,
            List<PlnSchedulingOperationEntity> operations) {
        Map<Long, List<PlnSchedulingOperationEntity>> operationsByIssue = operations.stream()
                .filter(item -> item.getIssueId() != null)
                .collect(Collectors.groupingBy(PlnSchedulingOperationEntity::getIssueId));
        return details.stream().map(detail -> {
                    List<PlnSchedulingOperationEntity> rows = operationsByIssue
                            .getOrDefault(detail.getIssueId(), List.of());
                    LocalDateTime startTime = detail.getOperationStartTime() != null
                            ? detail.getOperationStartTime() : firstStart(rows);
                    LocalDateTime endTime = detail.getOperationEndTime() != null
                            ? detail.getOperationEndTime() : lastEnd(rows);
                    long lateDays = detail.getDemandTime() == null || endTime == null ? 0L
                            : Math.max(0L, ChronoUnit.DAYS.between(detail.getDemandTime(), endTime.toLocalDate()));
                    long activeMinutes = rows.stream().map(PlnSchedulingOperationEntity::getActiveMinutes)
                            .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
                    long spanMinutes = startTime == null || endTime == null ? 0L
                            : Math.max(0L, Duration.between(startTime, endTime).toMinutes());
                    long waitingMinutes = Math.max(0L, spanMinutes - activeMinutes);
                    boolean hardBlocked = Boolean.TRUE.equals(detail.getMaterialHardBlocked())
                            || Boolean.TRUE.equals(detail.getOperationHardBlocked())
                            || rows.stream().anyMatch(item -> Boolean.TRUE.equals(item.getHardBlocked()));
                    return new PlanSchedulingAgentDTO.PlanImpact()
                            .setIssueId(detail.getIssueId())
                            .setIssueNo(detail.getIssueNo())
                            .setCustomerName(detail.getCustomerName())
                            .setProductCode(detail.getProductCode())
                            .setProductName(detail.getProductName())
                            .setDemandDate(detail.getDemandTime())
                            .setScheduledStartTime(startTime)
                            .setScheduledEndTime(endTime)
                            .setLateDays(lateDays)
                            .setWaitingMinutes(waitingMinutes)
                            .setOperationCount(detail.getOperationCount() != null
                                    ? detail.getOperationCount() : rows.size())
                            .setMaterialConstraintStatus(detail.getMaterialConstraintStatus())
                            .setHardBlocked(hardBlocked)
                            .setRiskSummary(planRiskSummary(detail, lateDays, waitingMinutes, hardBlocked));
                })
                .sorted(Comparator
                        .comparing((PlanSchedulingAgentDTO.PlanImpact item) ->
                                Boolean.TRUE.equals(item.getHardBlocked())).reversed()
                        .thenComparing(PlanSchedulingAgentDTO.PlanImpact::getLateDays,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PlanSchedulingAgentDTO.PlanImpact::getWaitingMinutes,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PlanSchedulingAgentDTO.PlanImpact::getDemandDate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PlanSchedulingAgentDTO.PlanImpact::getIssueNo,
                                Comparator.nullsLast(String::compareTo)))
                .limit(MAX_PLAN_IMPACTS)
                .toList();
    }

    private LocalDateTime firstStart(List<PlnSchedulingOperationEntity> rows) {
        return rows.stream().map(PlnSchedulingOperationEntity::getScheduledStartTime)
                .filter(Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
    }

    private LocalDateTime lastEnd(List<PlnSchedulingOperationEntity> rows) {
        return rows.stream().map(PlnSchedulingOperationEntity::getScheduledEndTime)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null);
    }

    private String planRiskSummary(PlnSchedulingTaskDetailEntity detail, long lateDays,
                                   long waitingMinutes, boolean hardBlocked) {
        List<String> reasons = new ArrayList<>();
        if (Boolean.TRUE.equals(detail.getMaterialHardBlocked())) {
            reasons.add(StrUtil.blankToDefault(detail.getMaterialConstraintSummary(), "物料约束硬阻断"));
        }
        if (Boolean.TRUE.equals(detail.getOperationHardBlocked())) {
            reasons.add(StrUtil.blankToDefault(detail.getOperationScheduleSummary(), "工序排程硬阻断"));
        }
        if (lateDays > 0) {
            reasons.add("预计延期" + lateDays + "天");
        }
        if (waitingMinutes > 0) {
            reasons.add("工序等待" + waitingMinutes + "分钟");
        }
        if (reasons.isEmpty()) {
            return hardBlocked ? "存在工序级硬阻断，需下钻工序证据" : "预计按期完成，未发现硬阻断";
        }
        return String.join("；", reasons);
    }

    private List<PlanSchedulingAgentDTO.ResourceHotspot> resourceHotspots(String metricsSnapshot) {
        try {
            JsonNode root = objectMapper.readTree(metricsSnapshot);
            JsonNode nodes = root.path("resourceHotspots");
            if (!nodes.isArray()) {
                return List.of();
            }
            List<PlanSchedulingAgentDTO.ResourceHotspot> result = new ArrayList<>();
            for (JsonNode node : nodes) {
                result.add(new PlanSchedulingAgentDTO.ResourceHotspot()
                        .setResourceType(textValue(node, "resourceType"))
                        .setResourceCode(textValue(node, "resourceCode"))
                        .setResourceName(textValue(node, "resourceName"))
                        .setLoadDate(node.path("loadDate").isTextual()
                                ? java.time.LocalDate.parse(node.path("loadDate").asText()) : null)
                        .setUtilizationRate(node.path("utilizationRate").isNumber()
                                ? node.path("utilizationRate").decimalValue() : null)
                        .setPlannedMinutes(longValue(node, "plannedMinutes"))
                        .setAvailableMinutes(longValue(node, "availableMinutes"))
                        .setOverloadMinutes(longValue(node, "overloadMinutes"))
                        .setLoadStatus(textValue(node, "loadStatus"))
                        .setCapacityVerified(node.path("capacityVerified").isBoolean()
                                ? node.path("capacityVerified").booleanValue() : null)
                        .setCapacityExplanation(textValue(node, "capacityExplanation"))
                        .setOperationCount(integerValue(node, "operationCount"))
                        .setIssueCount(integerValue(node, "issueCount")));
            }
            return result;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private long resourceDayCount(String metricsSnapshot) {
        try {
            return objectMapper.readTree(metricsSnapshot).path("resourceDayCount").asLong();
        } catch (Exception ex) {
            return 0L;
        }
    }

    private String textValue(JsonNode node, String key) {
        return node.path(key).isTextual() ? node.path(key).asText() : null;
    }

    private Long longValue(JsonNode node, String key) {
        return node.path(key).isNumber() ? node.path(key).longValue() : null;
    }

    private Integer integerValue(JsonNode node, String key) {
        return node.path(key).isNumber() ? node.path(key).intValue() : null;
    }

    private String candidateFingerprint(PlnSchedulingScenarioEntity scenario, PlnSchedulingTaskEntity task,
                                        List<PlnSchedulingTaskDetailEntity> details,
                                        List<PlnSchedulingOperationEntity> operations) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("scenarioId", scenario.getId());
        header.put("sourceFingerprint", scenario.getSourceFingerprint());
        header.put("inputFingerprint", scenario.getInputFingerprint());
        header.put("rule", scenario.getRuleCode() + "@" + scenario.getRuleVersion());
        header.put("taskId", task.getId());
        if (task.getParentCandidateTaskId() != null) {
            header.put("parentTaskId", task.getParentCandidateTaskId());
            header.put("derivationRequestId", task.getDerivationRequestId());
            header.put("derivationRequestFingerprint", task.getDerivationRequestFingerprint());
            header.put("derivationReason", task.getDerivationReason());
            header.put("derivationDiff", task.getDerivationDiffSnapshot());
        }
        header.put("taskStatus", task.getTaskStatus());
        header.put("candidateCode", task.getCandidateCode());
        header.put("strategy", task.getSequenceStrategy());
        header.put("selected", task.getSelectedCandidate());
        header.put("weights", task.getObjectiveWeightsSnapshot());
        header.put("metrics", task.getCandidateMetricsSnapshot());
        header.put("score", task.getCandidateScore());
        header.put("rank", task.getCandidateRank());
        header.put("feasible", task.getCandidateFeasible());
        List<Map<String, Object>> detailFacts = details.stream().map(item -> {
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("id", item.getId());
            fact.put("issueId", item.getIssueId());
            fact.put("issueNo", item.getIssueNo());
            fact.put("demandTime", item.getDemandTime());
            fact.put("scheduledDate", item.getScheduledDate());
            fact.put("scheduledEndDate", item.getScheduledEndDate());
            fact.put("operationStartTime", item.getOperationStartTime());
            fact.put("operationEndTime", item.getOperationEndTime());
            fact.put("operationCount", item.getOperationCount());
            fact.put("materialStatus", item.getMaterialConstraintStatus());
            fact.put("materialBlocked", item.getMaterialHardBlocked());
            fact.put("operationBlocked", item.getOperationHardBlocked());
            fact.put("materialFingerprint", item.getMaterialLineageFingerprint());
            return fact;
        }).toList();
        List<Map<String, Object>> operationFacts = operations.stream().map(item -> {
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("id", item.getId());
            fact.put("issueId", item.getIssueId());
            fact.put("process", item.getProcessCode());
            fact.put("seq", item.getOperationSeq());
            fact.put("resource", item.getResourceCode());
            fact.put("slot", item.getResourceSlotNo());
            fact.put("device", item.getDeviceCode());
            fact.put("employee", item.getEmployeeCode());
            fact.put("tooling", item.getToolingCodes());
            fact.put("start", item.getScheduledStartTime());
            fact.put("end", item.getScheduledEndTime());
            fact.put("hardBlocked", item.getHardBlocked());
            fact.put("constraintSummary", item.getConstraintSummary());
            fact.put("calibration", item.getDurationCalibrationFingerprint());
            fact.put("resourceEvidence", item.getCandidateResourceSnapshot());
            return fact;
        }).toList();
        return hash(json(List.of(header, detailFacts, operationFacts)));
    }

    private String scenarioFingerprint(PlnSchedulingScenarioEntity scenario,
                                       List<PlanSchedulingAgentDTO.CandidateEvidence> candidates) {
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("id", scenario.getId());
        fact.put("scenarioNo", scenario.getScenarioNo());
        fact.put("factory", scenario.getFactoryCode());
        fact.put("status", scenario.getScenarioStatus());
        fact.put("source", scenario.getSourceFingerprint());
        fact.put("ruleCode", scenario.getRuleCode());
        fact.put("inputFingerprint", scenario.getInputFingerprint());
        fact.put("ruleVersion", scenario.getRuleVersion());
        fact.put("selectedTaskId", scenario.getSelectedTaskId());
        fact.put("selectionFingerprint", scenario.getSelectionFingerprint());
        fact.put("expiresTime", scenario.getExpiresTime());
        fact.put("candidates", candidates.stream().map(PlanSchedulingAgentDTO.CandidateEvidence::getCandidateFingerprint).toList());
        return hash(json(fact));
    }

    private String tradeoff(PlnSchedulingTaskEntity task) {
        try {
            JsonNode m = objectMapper.readTree(task.getCandidateMetricsSnapshot());
            if (m.path("hardBlockedCount").asLong() > 0) {
                return "存在" + m.path("hardBlockedCount").asLong() + "项硬阻断，禁止正式锁定";
            }
            return "延期" + m.path("totalLateDays").asLong() + "天，换型" + m.path("changeoverMinutes").asLong()
                    + "分钟，周期" + m.path("makespanMinutes").asLong() + "分钟，等待"
                    + m.path("waitingMinutes").asLong() + "分钟，在制暴露" + m.path("wipExposureMinutes").asLong()
                    + "分钟，加班" + m.path("requiredOvertimeMinutes").asLong() + "分钟，扰动"
                    + m.path("disturbanceMinutes").asLong() + "分钟"
                    + (m.path("capacityUnverifiedResourceDays").asLong() > 0
                    ? "；" + m.path("capacityUnverifiedResourceDays").asLong() + "个资源日容量待核验" : "");
        } catch (Exception ex) {
            return "候选指标快照无法解释，禁止仅凭综合分发布，应重新查询情景";
        }
    }

    private void validatePublish(PlanSchedulingAgentDTO.PublishCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getFactoryCode())
                && StrUtil.isNotBlank(command.getScenarioNo()) && StrUtil.isNotBlank(command.getRequestId())
                && command.getSelectedTaskId() != null && StrUtil.isNotBlank(command.getExpectedScenarioFingerprint())
                && StrUtil.isNotBlank(command.getExpectedSelectionFingerprint()) && command.getAgentApprovalId() != null
                && StrUtil.isNotBlank(command.getAgentApprovalNo()) && StrUtil.isNotBlank(command.getApplicantCode())
                && StrUtil.isNotBlank(command.getApproverCode()), "A3正式锁定命令或Agent审批证据不完整");
        require(!Objects.equals(command.getApplicantCode().trim(), command.getApproverCode().trim()),
                "A3正式锁定禁止申请人自批");
        requireFactory(command.getFactoryCode());
    }

    private Map<String, Object> publishFingerprintFields(PlanSchedulingAgentDTO.PublishCommand command) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("factory", command.getFactoryCode().trim());
        fields.put("scenario", command.getScenarioNo().trim());
        fields.put("task", command.getSelectedTaskId());
        fields.put("scenarioFingerprint", command.getExpectedScenarioFingerprint());
        fields.put("selectionFingerprint", command.getExpectedSelectionFingerprint());
        fields.put("approvalId", command.getAgentApprovalId());
        fields.put("approvalNo", command.getAgentApprovalNo());
        fields.put("applicant", command.getApplicantCode());
        fields.put("approver", command.getApproverCode());
        return fields;
    }

    private PlanSchedulingAgentDTO.PublishReceipt receipt(PlnSchedulingScenarioEntity scenario) {
        return new PlanSchedulingAgentDTO.PublishReceipt().setScenarioId(scenario.getId())
                .setScenarioNo(scenario.getScenarioNo()).setTaskId(scenario.getSelectedTaskId())
                .setScenarioStatus(scenario.getScenarioStatus()).setPublishRequestId(scenario.getPublishRequestId())
                .setPublishRequestFingerprint(scenario.getPublishRequestFingerprint())
                .setAgentApprovalNo(scenario.getAgentApprovalNo()).setPublishedBy(scenario.getPublishedBy())
                .setPublishedTime(scenario.getPublishedTime());
    }

    private PlanSchedulingAgentDTO.ScenarioQuery queryOf(String factory, String scenarioNo) {
        PlanSchedulingAgentDTO.ScenarioQuery query = new PlanSchedulingAgentDTO.ScenarioQuery();
        query.setFactoryCode(factory);
        query.setScenarioNo(scenarioNo);
        return query;
    }

    private void requireFactory(String factory) {
        String current = FactoryContextHolder.getFactoryCode();
        require(StrUtil.isBlank(current) || Objects.equals(current, factory.trim()), "请求工厂超出当前数据范围");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("APS证据序列化失败", ex);
        }
    }

    private String percent(BigDecimal value) {
        return Objects.requireNonNullElse(value, BigDecimal.ZERO).stripTrailingZeros().toPlainString() + "%";
    }

    private PlanSchedulingAgentDTO.ObjectiveTemplateOption templateFromScenario(
            PlnSchedulingScenarioEntity scenario) {
        if (scenario == null || StrUtil.isBlank(scenario.getInputSnapshot())) return null;
        try {
            PlnSchedulingScenarioInput input = objectMapper.readValue(
                    scenario.getInputSnapshot(), PlnSchedulingScenarioInput.class);
            return templateFromSnapshot(input.getObjectiveTemplateSnapshot());
        } catch (Exception ex) {
            throw new CheckedException("排产情景输入快照无法解析，拒绝省略行业模板证据", ex);
        }
    }

    private PlanSchedulingAgentDTO.ObjectiveTemplateOption templateFromSnapshot(String snapshot) {
        if (StrUtil.isBlank(snapshot)) return null;
        try {
            return objectiveTemplateOption(objectMapper.readValue(snapshot, PlnObjectiveTemplate.class));
        } catch (Exception ex) {
            throw new CheckedException("行业目标模板冻结证据无法解析", ex);
        }
    }

    private PlanSchedulingAgentDTO.ObjectiveTemplateOption objectiveTemplateOption(PlnObjectiveTemplate template) {
        try {
            List<PlnSchedulingCandidateProfileDTO> profiles = objectMapper.readValue(
                    template.getProfilesJson(), objectMapper.getTypeFactory().constructCollectionType(
                            List.class, PlnSchedulingCandidateProfileDTO.class));
            return new PlanSchedulingAgentDTO.ObjectiveTemplateOption()
                    .setTemplateId(template.getId())
                    .setTemplateCode(template.getTemplateCode())
                    .setTemplateName(template.getTemplateName())
                    .setIndustryCode(template.getIndustryCode())
                    .setVersionNo(template.getVersionNo())
                    .setCandidateProfiles(profiles)
                    .setTemplateFingerprint(objectiveTemplateService.fingerprint(template));
        } catch (CheckedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CheckedException("行业目标模板候选内容无法解析：" + template.getTemplateCode(), ex);
        }
    }

    private String templateVersionRef(PlanSchedulingAgentDTO.ObjectiveTemplateOption template) {
        return template == null ? null : template.getTemplateCode() + "@" + template.getVersionNo()
                + "[" + template.getIndustryCode() + "]";
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new CheckedException("APS证据摘要计算失败", ex);
        }
    }

    private record MetricView(PlanSchedulingAgentDTO.CandidateEvidence candidate, JsonNode metrics) {
        long value(String key) {
            return metrics.path(key).asLong();
        }
    }
}
