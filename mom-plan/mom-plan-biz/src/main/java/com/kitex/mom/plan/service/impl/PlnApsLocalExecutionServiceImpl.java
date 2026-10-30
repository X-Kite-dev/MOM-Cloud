package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesApsLocalScheduleDTO;
import com.kitex.mom.mes.dto.MesApsPublicationProbeDTO;
import com.kitex.mom.plan.dto.PlnApsLocalExecutionDTO;
import com.kitex.mom.plan.dto.PlnApsLocalReplanDTO;
import com.kitex.mom.plan.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 局部重排执行取证。原回执、当前候选时刻对应、现场实际推进三条证据分开返回。
 * 不重发原命令、不补记PLAN、不把历史指纹与当前执行指纹直接相等作为成功条件。
 */
@Service
@RequiredArgsConstructor
public class PlnApsLocalExecutionServiceImpl implements PlnApsLocalExecutionService {
    private static final int PAGE_SIZE = 5;
    private final PlnApsLocalReplanWorkbenchService workbenchService;
    private final PlnShortageLocalReplanService shortageService;
    private final PlnResourceLocalReplanService resourceService;
    private final PlnQualityLocalReplanService qualityService;
    private final PlnInsertionLocalReplanService insertionService;
    private final RemoteMesTaskService mesService;
    private final ObjectMapper objectMapper;

    @Override
    public PlnApsLocalExecutionDTO.Page page(Long apsCaseId, String candidateId, int current, String pendingRequestId) {
        var view = workbenchService.detail(apsCaseId); // 同时核验统一案件、工厂及原业务查看岗位。
        requireMesPermission();
        var candidate = view.getCandidates().stream().filter(row -> Objects.equals(row.getId(), candidateId)).findFirst()
                .orElseThrow(() -> new CheckedException("候选不属于当前统一案件的原业务情景"));
        JsonNode source = objectMapper.valueToTree(readDetail(view.getDomain(), Long.valueOf(candidateId)));
        String factory = text(source, "factoryCode");
        require(Objects.equals(factory, FactoryContextHolder.getFactoryCode()), "原候选不属于当前实际工厂");
        String publicationId = text(source, "publishRequestId");
        if (StrUtil.isNotBlank(pendingRequestId)) {
            require(pendingRequestId.length() <= 64 && (publicationId == null || publicationId.equals(pendingRequestId)),
                    "待核对发布号与原PLAN业务记录不一致");
            if (publicationId == null) publicationId = pendingRequestId;
        }
        List<Target> targets = targets(view.getDomain(), source);
        require(current >= 1, "执行取证页码必须为正整数");
        int from = (int) Math.min(targets.size(), (current - 1L) * PAGE_SIZE);
        int to = Math.min(targets.size(), from + PAGE_SIZE);
        List<PlnApsLocalExecutionDTO.PlanEvidence> records = new ArrayList<>();
        boolean mps = "RESOURCE".equals(view.getDomain()) && isMps(source);
        for (Target target : targets.subList(from, to)) {
            var lines = candidate.getLines().stream().filter(line -> Objects.equals(target.issueNo(), line.getIssueNo())).toList();
            records.add(observe(factory, candidate, target, lines, publicationId, mps));
        }
        return new PlnApsLocalExecutionDTO.Page().setCaseId(view.getCaseId()).setCandidateId(candidateId)
                .setCandidateStatus(candidate.getStatus()).setPublicationRequestId(publicationId)
                .setEffectScenarioId(view.getScenarioId()).setCurrent(current).setPageSize(PAGE_SIZE)
                .setTotal(targets.size()).setRecords(records);
    }

    private Object readDetail(String domain, Long id) {
        return switch (domain) {
            case "SHORTAGE" -> shortageService.detail(id);
            case "RESOURCE" -> resourceService.detail(id);
            case "QUALITY" -> qualityService.detail(id);
            case "INSERTION" -> insertionService.detail(id);
            default -> throw new CheckedException("当前来源没有原业务执行取证合同");
        };
    }

    private List<Target> targets(String domain, JsonNode source) {
        List<Target> targets = new ArrayList<>();
        if (!"INSERTION".equals(domain)) {
            targets.add(new Target(text(source, "issueId"), text(source, "issueNo"), true,
                    text(source, "mesPublishedFingerprint"), null));
        } else {
            require(source.path("issues").isArray() && !source.path("issues").isEmpty(), "原插单候选没有完整逐计划范围");
            for (JsonNode issue : source.path("issues")) {
                require(Objects.equals(text(issue, "caseId"), text(source, "id")), "逐计划影响不属于原候选");
                JsonNode before = parse(text(issue, "mesBeforeSnapshot"));
                Boolean present = before.path("mesTaskPresent").isBoolean() ? before.path("mesTaskPresent").booleanValue() : null;
                targets.add(new Target(text(issue, "issueId"), text(issue, "issueNo"), present,
                        text(issue, "mesPublishedFingerprint"), text(issue, "issueId")));
            }
        }
        Set<String> ids = new HashSet<>(), nos = new HashSet<>();
        for (Target target : targets) require(target.issueId() != null && target.issueId().matches("[1-9][0-9]{0,18}")
                && StrUtil.isNotBlank(target.issueNo()) && ids.add(target.issueId()) && nos.add(target.issueNo()),
                "原业务逐计划范围缺失或重复，不能猜测归属");
        targets.sort(Comparator.comparing(Target::issueId));
        return targets;
    }

    private PlnApsLocalExecutionDTO.PlanEvidence observe(String factory, PlnApsLocalReplanDTO.Candidate candidate,
            Target target, List<PlnApsLocalReplanDTO.OperationDiff> lines, String publicationId, boolean mps) {
        var evidence = new PlnApsLocalExecutionDTO.PlanEvidence().setIssueId(target.issueId()).setIssueNo(target.issueNo())
                .setPlannedOperationCount(lines.size()).setReceipts(List.of()).setOperations(List.of())
                .setScheduleState("UNKNOWN").setProgressState("DATA_GAP");
        List<String> gaps = new ArrayList<>();
        audit(factory, candidate, target, publicationId, mps, evidence, gaps);
        try {
            R<MesApsLocalScheduleDTO.StateBundle> response = mesService.apsOperationStates(target.issueNo(), factory);
            require(response != null && response.isOk() && response.getData() != null, "MES执行事实不可用");
            var bundle = response.getData();
            require(Objects.equals(bundle.getPlanNo(), target.issueNo()) && Objects.equals(bundle.getFactoryCode(), factory),
                    "MES执行事实的计划或工厂不匹配");
            require(bundle.getMesTaskPresent() != null, "MES工单存在性未返回");
            evidence.setMesAvailable(true).setMesTaskPresent(bundle.getMesTaskPresent());
            if (!bundle.getMesTaskPresent()) {
                require(bundle.getOperations() == null || bundle.getOperations().isEmpty(), "MES无工单标记与工序事实矛盾");
                evidence.setProgressState("NO_TASK");
            } else current(candidate, lines, bundle, evidence, gaps);
        } catch (RuntimeException ex) {
            evidence.setMesAvailable(false);
            gaps.add("MES执行事实暂不可核验，请检查服务、岗位权限及原计划映射");
        }
        return evidence.setGaps(List.copyOf(gaps));
    }

    private void audit(String factory, PlnApsLocalReplanDTO.Candidate candidate, Target target, String publicationId,
            boolean mps, PlnApsLocalExecutionDTO.PlanEvidence evidence, List<String> gaps) {
        if (publicationId == null) { evidence.setReceiptState("NOT_PUBLISHED"); return; }
        if (Boolean.FALSE.equals(target.receiptRequired())) { evidence.setReceiptState("NOT_REQUIRED"); return; }
        if (target.receiptRequired() == null) { evidence.setReceiptState("UNKNOWN"); gaps.add("原插单工单存在性快照缺失，不能判断是否需要同步回执"); return; }
        List<String> ids = new ArrayList<>();
        if (target.requestSuffix() != null) ids.add(publicationId + ":MES:" + target.requestSuffix());
        else {
            ids.add(publicationId + ":MES");
            if (mps) for (int segment = 1; segment <= 10; segment++) ids.add(publicationId + ":MES-RETRY:" + segment);
        }
        try {
            var query = new MesApsPublicationProbeDTO.AuditQuery()
                    .setScope(new MesApsPublicationProbeDTO.Scope().setCaseId(Long.valueOf(candidate.getId()))
                            .setPlanNo(target.issueNo()).setFactoryCode(factory))
                    .setRequestIds(ids).setExpectedAfterFingerprint(target.afterFingerprint());
            var response = mesService.auditApsPublication(query);
            require(response != null && response.isOk() && response.getData() != null, "原MES应用账不可读取");
            var reply = response.getData();
            require(Set.of("NOT_OBSERVED", "VERIFIED", "OBSERVED_UNRECONCILED", "FINGERPRINT_MISMATCH", "AMBIGUOUS").contains(reply.getState())
                    && reply.getRecords() != null, "原MES应用账核对状态无效");
            evidence.setReceiptState(reply.getState()).setReceipts(reply.getRecords().stream().map(row ->
                    new PlnApsLocalExecutionDTO.ReceiptEvidence().setRequestId(row.getRequestId()).setAppliedBy(row.getAppliedBy())
                            .setAppliedTime(iso(row.getAppliedTime())).setUpdatedOperationCount(row.getUpdatedOperationCount())
                            .setFingerprintMatched(row.getFingerprintMatched())).toList());
        } catch (RuntimeException ex) {
            evidence.setReceiptState("UNAVAILABLE");
            gaps.add("原MES应用回执暂不可读取，不能认定同步失败或重新发送");
        }
    }

    private void current(PlnApsLocalReplanDTO.Candidate candidate, List<PlnApsLocalReplanDTO.OperationDiff> lines,
            MesApsLocalScheduleDTO.StateBundle bundle, PlnApsLocalExecutionDTO.PlanEvidence evidence, List<String> gaps) {
        require(!lines.isEmpty(), "原候选工序范围缺失");
        boolean nodeMode = lines.stream().anyMatch(line -> StrUtil.isNotBlank(line.getRouteNodeCode()));
        require(lines.stream().allMatch(line -> nodeMode == StrUtil.isNotBlank(line.getRouteNodeCode())), "原候选冻结节点合同不完整");
        var states = PlnFrozenRouteNodeIdentity.states(bundle);
        Map<String, MesApsLocalScheduleDTO.OperationState> actual = new LinkedHashMap<>();
        for (var state : states.values()) {
            String key = nodeMode ? state.getSourceRouteNodeCode() : state.getRouteCode();
            require(StrUtil.isNotBlank(key) && actual.putIfAbsent(key, state) == null, "MES节点或旧主工序不能唯一对应");
        }
        Set<String> expected = new HashSet<>();
        for (var line : lines) require(expected.add(nodeMode ? line.getRouteNodeCode() : line.getProcessCode()), "原候选冻结工序重复");
        boolean complete = expected.equals(actual.keySet());
        if (!complete) gaps.add("MES与原候选的完整工序范围不一致，当前匹配子集不能代表全部计划");
        List<PlnApsLocalExecutionDTO.OperationEvidence> operations = new ArrayList<>();
        int matched = 0, started = 0, finished = 0;
        boolean scheduleMatched = complete, scheduleTimesComplete = true, actualTimesComplete = true;
        for (var line : lines) {
            var row = new PlnApsLocalExecutionDTO.OperationEvidence().setRouteNodeCode(line.getRouteNodeCode())
                    .setProcessCode(line.getProcessCode()).setProcessName(line.getProcessName())
                    .setExpectedStartTime(line.getProposedStartTime()).setExpectedEndTime(line.getProposedEndTime());
            var state = actual.get(nodeMode ? line.getRouteNodeCode() : line.getProcessCode());
            if (state == null || !Objects.equals(line.getProcessCode(), state.getRouteCode())
                    || line.getOperationSeq() == null || !Objects.equals(line.getOperationSeq(), state.getRouteOrder())) {
                row.setGap("原节点、工序或序号未能唯一对应"); complete = false; scheduleMatched = false;
            } else {
                matched++;
                LocalDateTime start = null, end = null;
                try {
                    start = time(state.getActualStartTimeText(), state.getActualStartTime());
                    end = time(state.getActualFinishTimeText(), state.getActualFinishTime());
                } catch (RuntimeException ex) {
                    addGap(row, "实际开完工时间格式不可核验"); actualTimesComplete = false;
                }
                if (start != null) started++;
                if (end != null && start != null && !end.isBefore(start)) finished++;
                else if (end != null) { addGap(row, "实际开完工时间不完整或倒置"); actualTimesComplete = false; }
                LocalDateTime expectedStart = null, expectedEnd = null;
                try {
                    expectedStart = time(line.getProposedStartTime(), null);
                    expectedEnd = time(line.getProposedEndTime(), null);
                } catch (RuntimeException ex) {
                    addGap(row, "原候选计划时间格式不可核验"); scheduleTimesComplete = false;
                }
                Boolean same = expectedStart == null || expectedEnd == null || state.getPlannedStartTime() == null
                        || state.getPlannedEndTime() == null ? null : expectedStart.equals(state.getPlannedStartTime()) && expectedEnd.equals(state.getPlannedEndTime());
                if (same == null) { addGap(row, "候选或MES当前计划时刻缺失"); scheduleTimesComplete = false; }
                scheduleMatched &= Boolean.TRUE.equals(same);
                row.setCurrentStartTime(iso(state.getPlannedStartTime())).setCurrentEndTime(iso(state.getPlannedEndTime()))
                        .setScheduleMatched(same).setProduceTaskCode(state.getProduceTaskCode()).setMesStatus(state.getStatus())
                        .setActualStartTime(iso(start)).setActualFinishTime(iso(end)).setCompleteQuantity(state.getCompleteQuantity() == null ? null : state.getCompleteQuantity().toPlainString())
                        .setMovable(state.getMovable()).setBlockedReason(state.getBlockedReason())
                        .setStartDeviationMinutes(minutes(expectedStart, start)).setFinishDeviationMinutes(minutes(expectedEnd, end));
            }
            operations.add(row);
        }
        if (!complete || !actualTimesComplete) gaps.add("存在工序映射或实际时间证据缺口，不能判断全计划已完成");
        if (!scheduleTimesComplete) gaps.add("候选或MES当前计划时刻不完整，计划时刻一致性待核对");
        String progress = !complete || !actualTimesComplete ? "DATA_GAP" : finished == lines.size() ? "FINISHED" : started > 0 ? "EXECUTING" : "NOT_STARTED";
        String schedule = "ROLLED_BACK".equals(candidate.getStatus()) ? "ROLLED_BACK"
                : !"PUBLISHED".equals(candidate.getStatus()) ? "NOT_PUBLISHED" : !complete || !scheduleTimesComplete ? "UNKNOWN"
                : scheduleMatched ? "MATCHED" : "DRIFTED";
        evidence.setMatchedOperationCount(matched).setStartedOperationCount(started).setFinishedOperationCount(finished)
                .setOperations(operations).setProgressState(progress).setScheduleState(schedule);
    }

    private boolean isMps(JsonNode source) {
        if (!"EXECUTION".equals(text(source, "resourceType"))) return false;
        return PlnMpsRemainingEvidenceReader.FORMULA.equals(text(parse(text(source, "resourceEvidenceSnapshot")), "businessFormula"));
    }

    private JsonNode parse(String value) {
        if (value == null) return objectMapper.createObjectNode();
        try { return objectMapper.readTree(value); } catch (Exception ex) { throw new CheckedException("原业务冻结快照不可读取"); }
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isValueNode() && !node.path(field).isNull() && StrUtil.isNotBlank(node.path(field).asText()) ? node.path(field).asText() : null;
    }

    private static void addGap(PlnApsLocalExecutionDTO.OperationEvidence row, String message) {
        row.setGap(row.getGap() == null ? message : row.getGap() + "；" + message);
    }

    private static String iso(LocalDateTime value) { return value == null ? null : value.toString(); }
    private static LocalDateTime time(String value, LocalDateTime fallback) {
        if (StrUtil.isBlank(value)) return fallback;
        try { return LocalDateTime.parse(value.replace(' ', 'T')); }
        catch (RuntimeException ex) { throw new CheckedException("原业务时间格式不可核验"); }
    }
    private static Long minutes(LocalDateTime planned, LocalDateTime actual) { return planned == null || actual == null ? null : Duration.between(planned, actual).toMinutes(); }
    private static void requireMesPermission() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        require(auth != null && auth.isAuthenticated() && auth.getAuthorities().stream().anyMatch(row ->
                "mesTask:show".equals(row.getAuthority()) || "ROLE_ADMIN".equals(row.getAuthority())), "当前岗位缺少MES工单读取权限");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new CheckedException(message); }
    private record Target(String issueId, String issueNo, Boolean receiptRequired, String afterFingerprint, String requestSuffix) { }
}
