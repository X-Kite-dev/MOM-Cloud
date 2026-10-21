package com.kitex.mom.plan.agent.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesPlanSourceMappingDTO;
import com.kitex.mom.plan.agent.dto.PlanResourceReplanAdvisoryDTO;
import com.kitex.mom.plan.entity.*;
import com.kitex.mom.plan.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 把MES工单来源计划与PLAN既有资源异常局部重排案件连接为只读Agent证据。
 *
 * <p>本服务不创建资源异常、候选或排程。所有候选必须已经由资源异常工作台基于权威
 * 设备、人员、工装事实和MES执行状态生成，并继续经过A2选择和A3发布。</p>
 */
@Service
@RequiredArgsConstructor
public class PlanResourceReplanAdvisoryQueryService {

    public static final String TOOL_CODE = "PLAN_RESOURCE_REPLAN_ADVISORY_QUERY@1";
    private static final int MAX_CASES = 60;
    private static final int MAX_OPERATION_IMPACTS = 80;

    private final RemoteMesTaskService remoteMesTaskService;
    private final PlnIssueMapper issueMapper;
    private final PlnResourceReplanCaseMapper caseMapper;
    private final PlnResourceReplanLineMapper lineMapper;
    private final PlnSchedulingTaskMapper taskMapper;
    private final PlnSchedulingScenarioMapper scenarioMapper;
    private final ObjectMapper objectMapper;

    /**
     * 查询指定工单已经存在的PLAN资源异常影响、候选及受控下一步。
     */
    public PlanResourceReplanAdvisoryDTO.Evidence query(
            PlanResourceReplanAdvisoryDTO.Query query) {
        String factoryCode = requireFactory(query);
        String taskCode = query.getTaskCode().trim();
        R<MesPlanSourceMappingDTO> response = remoteMesTaskService.planSourceMapping(
                taskCode, null, factoryCode);
        require(response != null && response.isOk() && response.getData() != null,
                "MES未返回工单到PLAN来源计划的权威映射");
        MesPlanSourceMappingDTO mapping = response.getData();
        require(Objects.equals(factoryCode, mapping.getFactoryCode()),
                "MES来源映射工厂与当前factory-code不一致");

        List<String> gaps = new ArrayList<>();
        if (!Boolean.TRUE.equals(mapping.getMappingComplete())) {
            gaps.add("MES来源映射不完整：" + StrUtil.blankToDefault(
                    mapping.getMappingMessage(), mapping.getMappingStatus()));
        }
        List<PlnIssueEntity> issues = resolveIssues(factoryCode,
                Objects.requireNonNullElse(mapping.getAllocations(), List.of()), gaps);
        List<Long> issueIds = issues.stream().map(PlnIssueEntity::getId).toList();
        List<PlnResourceReplanCaseEntity> queriedCases = issueIds.isEmpty() ? List.of()
                : caseMapper.selectList(new LambdaQueryWrapper<PlnResourceReplanCaseEntity>()
                .eq(PlnResourceReplanCaseEntity::getFactoryCode, factoryCode)
                .in(PlnResourceReplanCaseEntity::getIssueId, issueIds)
                .orderByDesc(PlnResourceReplanCaseEntity::getId)
                .last("LIMIT " + (MAX_CASES + 1)));
        boolean truncated = queriedCases.size() > MAX_CASES;
        List<PlnResourceReplanCaseEntity> cases = queriedCases.stream().limit(MAX_CASES).toList();
        if (truncated) gaps.add("资源异常历史案件超过" + MAX_CASES + "条，本轮仅返回最新案件");

        Map<Long, PlnSchedulingTaskEntity> tasks = entitiesById(cases.stream()
                        .map(PlnResourceReplanCaseEntity::getProposalTaskId).filter(Objects::nonNull).distinct().toList(),
                taskMapper::selectBatchIds, PlnSchedulingTaskEntity::getId);
        Map<Long, PlnSchedulingScenarioEntity> scenarios = entitiesById(cases.stream()
                        .map(PlnResourceReplanCaseEntity::getScenarioId).filter(Objects::nonNull).distinct().toList(),
                scenarioMapper::selectBatchIds, PlnSchedulingScenarioEntity::getId);
        Map<Long, List<PlnResourceReplanLineEntity>> linesByCase = cases.isEmpty()
                ? Map.of() : lineMapper.selectList(new LambdaQueryWrapper<PlnResourceReplanLineEntity>()
                        .in(PlnResourceReplanLineEntity::getCaseId,
                                cases.stream().map(PlnResourceReplanCaseEntity::getId).toList())
                        .orderByAsc(PlnResourceReplanLineEntity::getCaseId)
                        .orderByAsc(PlnResourceReplanLineEntity::getOperationSeq)
                        .orderByAsc(PlnResourceReplanLineEntity::getId)).stream()
                .collect(Collectors.groupingBy(PlnResourceReplanLineEntity::getCaseId,
                        LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<PlnResourceReplanCaseEntity>> casesByIssue = cases.stream()
                .collect(Collectors.groupingBy(PlnResourceReplanCaseEntity::getIssueId,
                        LinkedHashMap::new, Collectors.toList()));

        List<PlanResourceReplanAdvisoryDTO.PlanAdvisory> plans = issues.stream()
                .map(issue -> advisory(issue, casesByIssue.getOrDefault(issue.getId(), List.of()),
                        tasks, scenarios, linesByCase, gaps)).toList();
        List<PlanResourceReplanAdvisoryDTO.ResourceReplanCandidate> allCandidates = plans.stream()
                .flatMap(item -> item.getCandidates().stream()).toList();
        PlanResourceReplanAdvisoryDTO.Summary summary = new PlanResourceReplanAdvisoryDTO.Summary()
                .setSourcePlanCount(issues.size())
                .setPlanWithCaseCount((int) plans.stream().filter(item -> item.getCandidateCount() > 0).count())
                .setCandidateCount(allCandidates.size())
                .setFeasibleCandidateCount((int) allCandidates.stream()
                        .filter(item -> Boolean.TRUE.equals(item.getCandidateFeasible())).count())
                .setAwaitingA2Count(countAdvisory(plans, "A2_SELECTION_REQUIRED"))
                .setAwaitingA3Count(countAdvisory(plans, "A3_REVIEW_REQUIRED"))
                .setPublishedCount(countAdvisory(plans, "PUBLISHED"))
                .setReassessmentRequiredCount(countAdvisory(plans, "REASSESS_REQUIRED"));

        List<String> refs = new ArrayList<>();
        refs.add("MES:mes_task:" + taskCode);
        issues.forEach(issue -> refs.add("PLAN:pln_issue:" + issue.getId()));
        plans.stream().flatMap(item -> item.getCandidates().stream()).forEach(item -> {
            refs.add("PLAN:pln_resource_replan_case:" + item.getCaseId());
            if (item.getScenarioId() != null) {
                refs.add("PLAN:pln_scheduling_scenario:" + item.getScenarioId());
            }
        });
        PlanResourceReplanAdvisoryDTO.Evidence evidence =
                new PlanResourceReplanAdvisoryDTO.Evidence()
                        .setToolCode(TOOL_CODE)
                        .setMetricDefinition("仅按MES工单保存的APS来源映射读取PLAN资源异常案件；候选日期、资源和排名只采信确定性PLAN求解结果。无案件时只提示受控评估，不由模型生成排程。")
                        .setFactoryCode(factoryCode).setTaskCode(taskCode)
                        .setMesMappingStatus(mapping.getMappingStatus())
                        .setMesMappingComplete(mapping.getMappingComplete())
                        .setMesMappingFingerprint(mapping.getMappingFingerprint())
                        .setObservedAt(LocalDateTime.now()).setSummary(summary).setPlans(plans)
                        .setDataGaps(gaps.stream().distinct().limit(50).toList())
                        .setEvidenceRefs(refs.stream().distinct().toList()).setTruncated(truncated);
        evidence.setEvidenceFingerprint(fingerprint(evidence));
        return evidence;
    }

    private List<PlnIssueEntity> resolveIssues(String factoryCode,
                                               List<MesPlanSourceMappingDTO.Allocation> allocations,
                                               List<String> gaps) {
        Map<String, MesPlanSourceMappingDTO.Allocation> unique = allocations.stream()
                .filter(item -> item.getIssueId() != null || StrUtil.isNotBlank(item.getIssueNo()))
                .collect(Collectors.toMap(item -> item.getIssueId() == null
                                ? "NO:" + item.getIssueNo() : "ID:" + item.getIssueId(),
                        Function.identity(), (left, right) -> left, LinkedHashMap::new));
        List<PlnIssueEntity> result = new ArrayList<>();
        for (MesPlanSourceMappingDTO.Allocation source : unique.values()) {
            PlnIssueEntity issue;
            if (source.getIssueId() != null) {
                issue = issueMapper.selectById(source.getIssueId());
            } else {
                List<PlnIssueEntity> matched = issueMapper.selectList(
                        new LambdaQueryWrapper<PlnIssueEntity>()
                                .eq(PlnIssueEntity::getFactoryCode, factoryCode)
                                .eq(PlnIssueEntity::getIssueNo, source.getIssueNo()).last("LIMIT 2"));
                if (matched.size() > 1) {
                    gaps.add("PLAN来源计划号重复，禁止猜测资源重排归属：" + source.getIssueNo());
                    continue;
                }
                issue = matched.isEmpty() ? null : matched.get(0);
            }
            if (issue == null || !Objects.equals(factoryCode, issue.getFactoryCode())) {
                gaps.add("MES来源计划不存在或不属于当前工厂：" + source.getIssueNo());
            } else if (result.stream().noneMatch(item -> Objects.equals(item.getId(), issue.getId()))) {
                result.add(issue);
            }
        }
        if (result.isEmpty()) gaps.add("MES工单没有可核验的PLAN来源计划");
        return result;
    }

    private PlanResourceReplanAdvisoryDTO.PlanAdvisory advisory(
            PlnIssueEntity issue, List<PlnResourceReplanCaseEntity> cases,
            Map<Long, PlnSchedulingTaskEntity> tasks,
            Map<Long, PlnSchedulingScenarioEntity> scenarios,
            Map<Long, List<PlnResourceReplanLineEntity>> linesByCase,
            List<String> evidenceGaps) {
        List<PlnResourceReplanCaseEntity> currentCases = currentCases(cases);
        List<PlanResourceReplanAdvisoryDTO.ResourceReplanCandidate> candidates = currentCases.stream()
                .map(item -> candidate(item, tasks.get(item.getProposalTaskId()),
                        scenarios.get(item.getScenarioId()),
                        linesByCase.getOrDefault(item.getId(), List.of()), evidenceGaps))
                .sorted(Comparator.comparing(
                                PlanResourceReplanAdvisoryDTO.ResourceReplanCandidate::getCandidateRank,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(PlanResourceReplanAdvisoryDTO.ResourceReplanCandidate::getCaseId,
                                Comparator.reverseOrder()))
                .toList();
        PlanResourceReplanAdvisoryDTO.ResourceReplanCandidate preferred = candidates.stream()
                .filter(item -> Boolean.TRUE.equals(item.getCandidateFeasible()))
                .filter(item -> Set.of("PROPOSED", "SELECTED").contains(item.getCaseStatus()))
                .findFirst().orElse(null);
        String status = advisoryStatus(candidates);
        List<String> planGaps = new ArrayList<>();
        if (currentCases.isEmpty()) {
            planGaps.add("当前来源计划没有正式资源异常重排案件，不能给出候选日期或资源");
        }
        return new PlanResourceReplanAdvisoryDTO.PlanAdvisory()
                .setIssueId(issue.getId()).setIssueNo(issue.getIssueNo())
                .setProductCode(issue.getProductCode()).setProductName(issue.getProductName())
                .setIssueStatus(issue.getStatus()).setLockStatus(issue.getLockStatus())
                .setLockTaskId(issue.getLockTaskId()).setAdvisoryStatus(status)
                .setNextControlledAction(nextAction(status)).setCandidateCount(candidates.size())
                .setFeasibleCandidateCount((int) candidates.stream()
                        .filter(item -> Boolean.TRUE.equals(item.getCandidateFeasible())).count())
                .setPreferredCandidateCaseId(preferred == null ? null : preferred.getCaseId())
                .setPreferredCandidateCode(preferred == null ? null : preferred.getCandidateCode())
                .setCandidates(candidates).setDataGaps(planGaps);
    }

    private PlanResourceReplanAdvisoryDTO.ResourceReplanCandidate candidate(
            PlnResourceReplanCaseEntity source, PlnSchedulingTaskEntity task,
            PlnSchedulingScenarioEntity scenario, List<PlnResourceReplanLineEntity> lines,
            List<String> evidenceGaps) {
        if (task != null && !Objects.equals(source.getFactoryCode(), task.getFactoryCode())) {
            evidenceGaps.add("资源重排案件" + source.getCaseNo() + "的候选任务不属于当前工厂");
            task = null;
        }
        if (scenario != null && !Objects.equals(source.getFactoryCode(), scenario.getFactoryCode())) {
            evidenceGaps.add("资源重排案件" + source.getCaseNo() + "的候选情景不属于当前工厂");
            scenario = null;
        }
        if (task == null) {
            evidenceGaps.add("资源重排案件" + source.getCaseNo() + "缺少可核验的确定性候选任务");
        }
        if (scenario == null) {
            evidenceGaps.add("资源重排案件" + source.getCaseNo() + "缺少可核验的资源重排情景");
        }
        boolean lineTruncated = lines.size() > MAX_OPERATION_IMPACTS;
        if (lineTruncated) evidenceGaps.add("资源重排案件" + source.getCaseNo() + "的工序影响明细已截断");
        if (StrUtil.isBlank(source.getResourceEvidenceFingerprint())) {
            evidenceGaps.add("资源重排案件" + source.getCaseNo() + "缺少权威资源证据指纹");
        }
        return new PlanResourceReplanAdvisoryDTO.ResourceReplanCandidate()
                .setCaseId(source.getId()).setCaseNo(source.getCaseNo()).setCaseStatus(source.getStatus())
                .setScenarioId(source.getScenarioId())
                .setScenarioNo(scenario == null ? null : scenario.getScenarioNo())
                .setScenarioStatus(scenario == null ? null : scenario.getScenarioStatus())
                .setResourceType(source.getResourceType()).setResourceCode(source.getResourceCode())
                .setResourceName(source.getResourceName())
                .setAuthoritySourceType(source.getEvidenceSourceType())
                .setAuthoritySourceNo(source.getEvidenceSourceNo())
                .setEventStartTime(source.getEventStartTime())
                .setExpectedRecoveryTime(source.getExpectedRecoveryTime()).setOpenEnded(source.getOpenEnded())
                .setProposedTime(source.getProposedTime()).setExpiresTime(source.getExpiresTime())
                .setCandidateCode(task == null ? null : task.getCandidateCode())
                .setCandidateName(task == null ? null : task.getCandidateName())
                .setCandidateScore(task == null ? null : task.getCandidateScore())
                .setCandidateRank(task == null ? null : task.getCandidateRank())
                .setCandidateFeasible(task == null ? null : task.getCandidateFeasible())
                .setSelectedCandidate(task == null ? null : task.getSelectedCandidate())
                .setDirectAffectedCount(source.getDirectAffectedCount())
                .setMovedOperationCount(source.getMovedOperationCount())
                .setReassignedOperationCount(source.getReassignedOperationCount())
                .setMaximumShiftMinutes(source.getMaximumShiftMinutes())
                .setOriginalCompletionTime(source.getOriginalCompletionTime())
                .setProposedCompletionTime(source.getProposedCompletionTime())
                .setDeliveryRisk(source.getDeliveryRisk())
                .setAffectedProcessCodes(source.getAffectedProcessCodes()).setReason(source.getReason())
                .setResourceEvidenceFingerprint(source.getResourceEvidenceFingerprint())
                .setProposalFingerprint(source.getProposalFingerprint())
                .setMesExecutionFingerprint(source.getMesExecutionFingerprint())
                .setOperationImpacts(lines.stream().limit(MAX_OPERATION_IMPACTS)
                        .map(this::operationImpact).toList())
                .setOperationImpactTruncated(lineTruncated);
    }

    private PlanResourceReplanAdvisoryDTO.OperationImpact operationImpact(
            PlnResourceReplanLineEntity source) {
        return new PlanResourceReplanAdvisoryDTO.OperationImpact()
                .setOriginalOperationId(source.getOriginalOperationId())
                .setProposalOperationId(source.getProposalOperationId())
                .setOperationSeq(source.getOperationSeq()).setProcessCode(source.getProcessCode())
                .setProcessName(source.getProcessName()).setDirectAffected(source.getDirectAffected())
                .setDownstreamAffected(source.getDownstreamAffected()).setActionType(source.getActionType())
                .setBlockedReason(source.getBlockedReason()).setOriginalStartTime(source.getOriginalStartTime())
                .setOriginalEndTime(source.getOriginalEndTime()).setProposedStartTime(source.getProposedStartTime())
                .setProposedEndTime(source.getProposedEndTime())
                .setOriginalResourceCode(source.getOriginalResourceCode())
                .setProposedResourceCode(source.getProposedResourceCode())
                .setOriginalDeviceCode(source.getOriginalDeviceCode())
                .setProposedDeviceCode(source.getProposedDeviceCode())
                .setOriginalEmployeeCode(source.getOriginalEmployeeCode())
                .setProposedEmployeeCode(source.getProposedEmployeeCode())
                .setOriginalToolingCodes(source.getOriginalToolingCodes())
                .setProposedToolingCodes(source.getProposedToolingCodes())
                .setResourceReassigned(source.getResourceReassigned()).setShiftMinutes(source.getShiftMinutes());
    }

    private String advisoryStatus(List<PlanResourceReplanAdvisoryDTO.ResourceReplanCandidate> candidates) {
        if (candidates.isEmpty()) return "NO_CASE";
        if (candidates.stream().anyMatch(item -> "PUBLISHED".equals(item.getCaseStatus()))) return "PUBLISHED";
        if (candidates.stream().anyMatch(item -> "SELECTED".equals(item.getCaseStatus()))) return "A3_REVIEW_REQUIRED";
        if (candidates.stream().anyMatch(item -> "PROPOSED".equals(item.getCaseStatus())
                && Boolean.TRUE.equals(item.getCandidateFeasible()))) return "A2_SELECTION_REQUIRED";
        if (candidates.stream().anyMatch(item -> "PROPOSED".equals(item.getCaseStatus()))) {
            return "NO_FEASIBLE_CANDIDATE";
        }
        return "REASSESS_REQUIRED";
    }

    /**
     * 同一计划可能保留多个历史异常周期；按雪花主键创建顺序只使用最新案件所属情景。
     */
    private List<PlnResourceReplanCaseEntity> currentCases(List<PlnResourceReplanCaseEntity> cases) {
        if (cases.isEmpty()) return List.of();
        PlnResourceReplanCaseEntity latest = cases.get(0);
        if (latest.getScenarioId() == null) return List.of(latest);
        return cases.stream().filter(item -> Objects.equals(
                latest.getScenarioId(), item.getScenarioId())).toList();
    }

    private String nextAction(String status) {
        return switch (status) {
            case "A2_SELECTION_REQUIRED" -> "进入资源异常重排工作台，由计划员比较候选并完成A2唯一选择";
            case "A3_REVIEW_REQUIRED" -> "由独立审批岗位重新核验证据后执行A3正式发布";
            case "PUBLISHED" -> "继续核对已发布候选的MES执行回写和交期影响";
            case "NO_FEASIBLE_CANDIDATE" -> "当前候选均有硬阻断，先处理阻断后重新进行受控评估";
            case "REASSESS_REQUIRED" -> "既有案件已失效或终结，应基于当前资源和MES事实重新评估";
            default -> "进入资源异常重排工作台，先基于权威资源事件执行受控影响评估";
        };
    }

    private int countAdvisory(List<PlanResourceReplanAdvisoryDTO.PlanAdvisory> plans, String status) {
        return (int) plans.stream().filter(item -> status.equals(item.getAdvisoryStatus())).count();
    }

    private <T> Map<Long, T> entitiesById(List<Long> ids,
                                          Function<List<Long>, List<T>> loader,
                                          Function<T, Long> idGetter) {
        if (ids.isEmpty()) return Map.of();
        return loader.apply(ids).stream().collect(Collectors.toMap(
                idGetter, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    private String requireFactory(PlanResourceReplanAdvisoryDTO.Query query) {
        require(query != null && StrUtil.isNotBlank(query.getFactoryCode())
                        && StrUtil.isNotBlank(query.getTaskCode()),
                "资源重排建议查询必须指定工厂和MES工单号");
        String actual = FactoryContextHolder.getFactoryCode();
        boolean valid = SecurityUtils.getUser() != null && SecurityUtils.getUser().getId() != null
                && TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip()
                && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(actual);
        require(valid, "资源重排建议查询必须实名登录并选择实际工厂");
        require(Objects.equals(actual, query.getFactoryCode().trim()),
                "资源重排建议查询工厂与当前factory-code不一致");
        return actual;
    }

    private String fingerprint(PlanResourceReplanAdvisoryDTO.Evidence evidence) {
        try {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("factoryCode", evidence.getFactoryCode());
            source.put("taskCode", evidence.getTaskCode());
            source.put("mappingFingerprint", evidence.getMesMappingFingerprint());
            source.put("summary", evidence.getSummary());
            source.put("plans", evidence.getPlans());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(source).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("资源重排建议证据指纹生成失败", exception);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
