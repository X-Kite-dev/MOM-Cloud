package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.plan.dto.*;
import com.kitex.mom.plan.mapper.PlnSchedulingEffectReviewMapper;
import com.kitex.mom.plan.mapper.PlnSchedulingScenarioMapper;
import com.kitex.mom.plan.mapper.PlnApsCaseMapper;
import com.kitex.mom.plan.entity.PlnSchedulingScenarioEntity;
import com.kitex.mom.plan.entity.PlnApsCaseEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.dto.PlnApsLocalReplanDTO;
import com.kitex.mom.plan.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 原重排领域到统一工作区的只读适配。
 *
 * <p>四个领域保留各自的求解、冻结、MES核验和A3发布。这里仅通过已有详情/情景合同读取，
 * 再按白名单投影字段；不复制完整实体快照，也不更新统一案件的流程状态冒充原领域成功。</p>
 */
@Service
@RequiredArgsConstructor
public class PlnApsLocalReplanWorkbenchServiceImpl implements PlnApsLocalReplanWorkbenchService {
    private final PlnApsCaseService caseService;
    private final PlnShortageLocalReplanService shortageService;
    private final PlnResourceLocalReplanService resourceService;
    private final PlnQualityLocalReplanService qualityService;
    private final PlnInsertionLocalReplanService insertionService;
    private final ObjectMapper objectMapper;
    private final PlnSchedulingEffectReviewMapper effectReviewMapper;
    private final PlnSchedulingAgentService schedulingAgentService;
    private final PlnApsReplanAdapter replanAdapter;
    private final PlnSchedulingScenarioMapper scenarioMapper;
    private final PlnApsCaseMapper apsCaseMapper;

    @Override
    public PlnApsLocalReplanDTO.Workbench detail(Long apsCaseId) {
        PlnApsCaseDTO apsCase = caseService.detail(apsCaseId);
        PlnApsCaseDTO.Source source = java.util.Optional.ofNullable(apsCase.getSources()).orElse(List.of()).stream()
                .filter(item -> "PRIMARY".equals(item.getSourceRole())).findFirst()
                .orElseThrow(() -> new CheckedException("当前案件缺少原业务来源"));
        if ("EFFECT_REVIEW".equals(source.getSourceType())) return effectWorkbench(apsCase, source);
        String domain = domain(source.getSourceType());
        String permissionPrefix = "plan_" + domain.toLowerCase(java.util.Locale.ROOT) + "Replan_";
        requirePermission(permissionPrefix + "view");
        Long sourceId = parseId(source.getSourceId());
        JsonNode origin = objectMapper.valueToTree(readDetail(domain, sourceId));
        require(Objects.equals(text(origin, "factoryCode"), apsCase.getFactoryCode()), "重排来源不属于当前案件工厂");
        String scenarioId = text(origin, "scenarioId");
        JsonNode scenario = scenarioId == null ? null : objectMapper.valueToTree(readScenario(domain, parseId(scenarioId)));
        JsonNode header = scenario == null ? null : scenario.path("scenario");
        if (header != null) require(Objects.equals(text(header, "factoryCode"), apsCase.getFactoryCode())
                && Objects.equals(text(header, "id"), scenarioId), "原重排情景身份或工厂不匹配");
        List<PlnApsLocalReplanDTO.Candidate> candidates = new ArrayList<>();
        if (scenario == null) candidates.add(candidate(origin, apsCase.getFactoryCode()));
        else {
            require(scenario.path("candidates").isArray() && !scenario.path("candidates").isEmpty(), "原重排情景未返回候选清单");
            for (JsonNode row : scenario.path("candidates")) {
                require(Objects.equals(text(row, "scenarioId"), scenarioId), "候选不属于原重排情景");
                candidates.add(candidate(row, apsCase.getFactoryCode()));
            }
            require(candidates.stream().anyMatch(item -> Objects.equals(item.getId(), source.getSourceId())),
                    "原来源候选已不在此情景中");
        }
        return new PlnApsLocalReplanDTO.Workbench().setCaseId(apsCase.getId()).setDomain(domain)
                .setSourceType(source.getSourceType()).setScenarioId(scenarioId)
                .setScenarioNo(text(header, "scenarioNo")).setScenarioStatus(text(header, "scenarioStatus"))
                .setSourceFingerprint(text(header, "sourceFingerprint")).setSelectionRequestId(text(header, "selectionRequestId"))
                .setLegacy(scenario == null)
                .setSelectPermission(permissionPrefix + ("SHORTAGE".equals(domain) ? "propose" : "assess"))
                .setPublishPermission(permissionPrefix + "publish").setCandidates(candidates);
    }

    /** 从已授权效果回评读取事实，只处理本次返回且被原服务确认可移动的偏差。 */
    private PlnApsLocalReplanDTO.Workbench effectWorkbench(PlnApsCaseDTO apsCase, PlnApsCaseDTO.Source source) {
        requirePermission("plan_plnSchedulingEffect_view");
        requirePermission("plan_resourceReplan_view");
        var review = effectReviewMapper.selectById(parseId(source.getSourceId()));
        require(review != null && Objects.equals(review.getFactoryCode(), apsCase.getFactoryCode()), "效果回评不属于当前案件工厂");
        PlanSchedulingAgentDTO.ScenarioQuery query = new PlanSchedulingAgentDTO.ScenarioQuery();
        query.setFactoryCode(apsCase.getFactoryCode());
        query.setScenarioId(review.getScenarioId());
        var effect = schedulingAgentService.effect(query);
        List<String> gaps = new ArrayList<>(java.util.Optional.ofNullable(effect.getDataGaps()).orElse(List.of()));
        if (!Boolean.TRUE.equals(effect.getAvailable())) gaps.add("原情景执行效果尚未就绪");
        Map<String, PlnApsLocalReplanDTO.EffectPlan> plans = new LinkedHashMap<>();
        for (var item : java.util.Optional.ofNullable(effect.getDeviationDispositions()).orElse(List.of())) {
            if (item.getIssueId() == null || !"LOCAL_REPLAN_CANDIDATE".equals(item.getDisposition())
                    || !Boolean.TRUE.equals(item.getReplanEligible())) continue;
            String id = item.getIssueId().toString();
            var plan = plans.computeIfAbsent(id, key -> new PlnApsLocalReplanDTO.EffectPlan()
                    .setIssueId(key).setIssueNo(item.getIssueNo()).setEligibleOperationCount(0));
            plan.setEligibleOperationCount(plan.getEligibleOperationCount() + 1);
        }
        return new PlnApsLocalReplanDTO.Workbench().setCaseId(apsCase.getId()).setDomain("RESOURCE")
                .setSourceType(source.getSourceType()).setEffectScenarioId(review.getScenarioId().toString())
                .setEffectFingerprint(effect.getEffectFingerprint()).setEffectPlans(List.copyOf(plans.values()))
                .setGaps(gaps).setCandidates(List.of()).setLegacy(false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PlnApsCaseDTO assessEffect(Long apsCaseId, PlnApsLocalReplanDTO.EffectAssessCommand command) {
        require(command != null && StrUtil.isNotBlank(command.getReason()) && command.getReason().trim().length() <= 500
                && StrUtil.isNotBlank(command.getRequestId()) && command.getRequestId().trim().length() <= 48,
                "请填写原评估请求号和不超过500字的评估依据");
        PlnApsCaseDTO apsCase = caseService.detail(apsCaseId);
        boolean closed = List.of("CANCELLED", "COMPLETED", "SUPERSEDED").contains(apsCase.getCaseStatus());
        var source = apsCase.getSources().stream().filter(item -> "PRIMARY".equals(item.getSourceRole())
                && "EFFECT_REVIEW".equals(item.getSourceType())).findFirst()
                .orElseThrow(() -> new CheckedException("只有执行效果来源支持此评估入口"));
        requirePermission("plan_plnSchedulingEffect_view");
        requirePermission("plan_resourceReplan_view");
        var review = effectReviewMapper.selectById(parseId(source.getSourceId()));
        require(review != null && Objects.equals(review.getFactoryCode(), apsCase.getFactoryCode()), "效果回评不属于当前案件工厂");
        // 已提交请求先交给原领域核验永久载荷，避免执行推进后被新的效果证据阻止回放。
        var existing = scenarioMapper.selectOne(new LambdaQueryWrapper<PlnSchedulingScenarioEntity>()
                .eq(PlnSchedulingScenarioEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(PlnSchedulingScenarioEntity::getFactoryCode, apsCase.getFactoryCode())
                .eq(PlnSchedulingScenarioEntity::getRequestId, command.getRequestId().trim()));
        if (existing == null) {
            require(!closed, "当前统一案件已归档，仅可查看原记录");
            PlnApsLocalReplanDTO.Workbench facts = effectWorkbench(apsCase, source);
            require(facts.getGaps().isEmpty() && Objects.equals(facts.getEffectFingerprint(), command.getExpectedEffectFingerprint()),
                    "执行效果存在缺口或已变化，请刷新后重新确认");
            require(facts.getEffectPlans().stream().anyMatch(plan -> Objects.equals(plan.getIssueId(), command.getIssueId())),
                    "所选计划不在当前可重排偏差范围中");
        }
        PlanResourceLocalReplanDTO.ExecutionDeviationAssessCommand request = new PlanResourceLocalReplanDTO.ExecutionDeviationAssessCommand();
        request.setSourceScenarioId(review.getScenarioId());
        request.setIssueId(parseId(command.getIssueId()));
        request.setRequestId(command.getRequestId().trim());
        request.setExpectedEffectFingerprint(command.getExpectedEffectFingerprint());
        request.setReason(command.getReason().trim());
        var scenario = resourceService.assessExecutionDeviation(request);
        require(scenario != null && scenario.getCandidates() != null && !scenario.getCandidates().isEmpty(), "原业务未返回可追溯候选");
        String candidateId = scenario.getCandidates().get(0).getId().toString();
        var prior = apsCaseMapper.selectOne(new LambdaQueryWrapper<PlnApsCaseEntity>()
                .eq(PlnApsCaseEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(PlnApsCaseEntity::getFactoryCode, apsCase.getFactoryCode())
                .eq(PlnApsCaseEntity::getLaunchRequestId, "LOCAL-APS#" + command.getRequestId().trim()));
        if (prior != null) {
            PlnApsCaseDTO result = caseService.detail(prior.getId());
            require(result.getSources().stream().anyMatch(item -> "EXECUTION_DEVIATION_CASE".equals(item.getSourceType())
                    && Objects.equals(item.getSourceId(), candidateId)), "原评估请求对应的统一案件来源不一致");
            return result;
        }
        require(!closed, "当前统一案件已归档，不能新建后续候选案件");
        PlnApsReplanDTO.LaunchCommand launch = new PlnApsReplanDTO.LaunchCommand();
        launch.setSourceType("EXECUTION_DEVIATION");
        launch.setSourceId(candidateId);
        launch.setRequestId("LOCAL-APS#" + command.getRequestId().trim());
        return replanAdapter.launchFromReplanSource(launch);
    }

    private Object readDetail(String domain, Long id) {
        return switch (domain) {
            case "SHORTAGE" -> shortageService.detail(id);
            case "RESOURCE" -> resourceService.detail(id);
            case "QUALITY" -> qualityService.detail(id);
            case "INSERTION" -> insertionService.detail(id);
            default -> throw new CheckedException("不支持的原重排领域");
        };
    }

    private Object readScenario(String domain, Long id) {
        return switch (domain) {
            case "SHORTAGE" -> shortageService.scenario(id);
            case "RESOURCE" -> resourceService.scenario(id);
            case "QUALITY" -> qualityService.scenario(id);
            case "INSERTION" -> insertionService.scenario(id);
            default -> throw new CheckedException("不支持的原重排领域");
        };
    }

    private PlnApsLocalReplanDTO.Candidate candidate(JsonNode row, String factory) {
        require(Objects.equals(text(row, "factoryCode"), factory), "候选不属于当前案件工厂");
        String id = text(row, "id");
        if (row.path("candidateFeasible").asBoolean(false)) {
            require(row.path("lines").isArray() && !row.path("lines").isEmpty(), "原可行候选缺少完整工序差异，暂不能办理");
        }
        require(id != null && id.matches("[1-9][0-9]{0,18}"), "原业务候选主键缺失或无效");
        List<PlnApsLocalReplanDTO.OperationDiff> lines = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (row.path("lines").isArray()) for (JsonNode line : row.path("lines")) {
            require(Objects.equals(text(line, "caseId"), id), "工序差异不属于原业务候选");
            String action = text(line, "actionType");
            counts.merge(action == null ? "UNKNOWN" : action, 1, Integer::sum);
            lines.add(new PlnApsLocalReplanDTO.OperationDiff().setId(text(line, "id"))
                    .setIssueNo(StrUtil.blankToDefault(text(line, "issueNo"), issueNo(row)))
                    .setRouteNodeCode(text(line, "sourceRouteNodeCode"))
                    .setProcessCode(text(line, "processCode")).setProcessName(text(line, "processName"))
                    .setOperationSeq(line.path("operationSeq").isNumber() ? line.path("operationSeq").intValue() : null)
                    .setActionType(action).setBlockedReason(text(line, "blockedReason"))
                    .setOriginalStartTime(text(line, "originalStartTime")).setOriginalEndTime(text(line, "originalEndTime"))
                    .setProposedStartTime(text(line, "proposedStartTime")).setProposedEndTime(text(line, "proposedEndTime"))
                    .setOriginalDeviceCode(text(line, "originalDeviceCode")).setProposedDeviceCode(text(line, "proposedDeviceCode"))
                    .setOriginalResourceCode(text(line, "originalResourceCode")).setProposedResourceCode(text(line, "proposedResourceCode"))
                    .setOriginalEmployeeCode(text(line, "originalEmployeeCode")).setProposedEmployeeCode(text(line, "proposedEmployeeCode"))
                    .setOriginalToolingCodes(text(line, "originalToolingCodes")).setProposedToolingCodes(text(line, "proposedToolingCodes"))
                    .setShiftMinutes(longValue(line, "shiftMinutes")));
        }
        // 参数只来自原领域合同，缺字段留空，不能由工作区补造审批或MES证据。
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String key : List.of("id", "factoryCode", "mrpId", "issueNo", "proposalFingerprint",
                "mesExecutionFingerprint", "resourceEvidenceFingerprint", "qualityEvidenceFingerprint", "materialEvidenceFingerprint")) {
            String value = text(row, key);
            if (value != null) parameters.put(key, value);
        }
        return new PlnApsLocalReplanDTO.Candidate().setId(id).setCaseNo(text(row, "caseNo"))
                .setName(StrUtil.blankToDefault(text(row, "candidateName"), "原业务方案"))
                .setStatus(text(row, "status")).setRank(row.path("candidateRank").isNumber() ? row.path("candidateRank").intValue() : null)
                .setScore(row.path("candidateScore").isNumber() ? row.path("candidateScore").decimalValue() : null)
                .setFeasible(row.path("candidateFeasible").isBoolean() && row.path("candidateFeasible").booleanValue())
                .setSelected(row.path("selectedCandidate").isBoolean() && row.path("selectedCandidate").booleanValue())
                .setApplicantCode(text(row, "applicantCode")).setPublisherCode(text(row, "publisherCode"))
                .setIssueNo(issueNo(row)).setFreezeUntil(text(row, "freezeUntil")).setExpiresTime(text(row, "expiresTime"))
                .setOriginalCompletionTime(text(row, "originalCompletionTime")).setProposedCompletionTime(text(row, "proposedCompletionTime"))
                .setDeliveryRisk(text(row, "deliveryRisk")).setPublishedTime(text(row, "publishedTime"))
                .setPublishRequestId(text(row, "publishRequestId"))
                .setActionCounts(counts).setPublishParameters(parameters).setLines(lines);
    }

    private static String issueNo(JsonNode row) {
        return StrUtil.blankToDefault(text(row, "issueNo"), text(row, "insertionIssueNo"));
    }

    private static String domain(String type) {
        return switch (type) {
            case "SHORTAGE_REPLAN_CASE" -> "SHORTAGE";
            case "RESOURCE_REPLAN_CASE", "EXECUTION_DEVIATION_CASE" -> "RESOURCE";
            case "QUALITY_REPLAN_CASE" -> "QUALITY";
            case "INSERTION_REPLAN_CASE" -> "INSERTION";
            default -> throw new CheckedException("当前来源尚未形成原业务局部重排候选，请先完成偏差评估");
        };
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.path(field).isValueNode() || node.path(field).isNull()) return null;
        String value = node.path(field).asText();
        return StrUtil.isBlank(value) ? null : value;
    }

    /** 兼容公共Jackson将Long输出为字符串，缺值不伪造为零。 */
    private static Long longValue(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) return null;
        try { return Long.valueOf(value); }
        catch (NumberFormatException ex) { throw new CheckedException("原工序移动分钟数格式不正确"); }
    }

    private static Long parseId(String id) {
        try {
            long value = Long.parseLong(id);
            require(value > 0, "原业务主键必须为正整数");
            return value;
        } catch (NumberFormatException ex) {
            throw new CheckedException("原业务主键格式不正确");
        }
    }

    private static void requirePermission(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        require(auth != null && auth.isAuthenticated() && auth.getAuthorities().stream().anyMatch(authority ->
                permission.equals(authority.getAuthority()) || "ROLE_ADMIN".equals(authority.getAuthority())),
                "当前岗位未配置原业务查看权限：" + permission);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }
}
