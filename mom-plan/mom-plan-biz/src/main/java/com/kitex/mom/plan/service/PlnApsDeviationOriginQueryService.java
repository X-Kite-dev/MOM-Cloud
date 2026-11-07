package com.kitex.mom.plan.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.plan.dto.PlnApsCaseDTO;
import com.kitex.mom.plan.entity.PlnResourceReplanCaseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 执行偏差局部重排的原评估依据。只读取原候选保存的证据白名单；
 * 不用当前MES事实补造历史，不把新局部情景混成原发布情景，不计算因果改善率。
 */
@Service
@RequiredArgsConstructor
public class PlnApsDeviationOriginQueryService {
    private static final int PAGE_SIZE = 20;
    private final PlnApsCaseService cases;
    private final PlnApsLocalReplanWorkbenchService workbench;
    private final PlnResourceLocalReplanService resources;
    private final ObjectMapper json;

    /**
     * 统一案件主来源与候选成员关系在同一只读数据库快照核验；
     * 工厂READ及资源领域原查看规则仍由原服务执行。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Origin read(Long caseId, Long candidateId, int current) {
        require(caseId != null && caseId > 0 && candidateId != null && candidateId > 0 && current > 0,
                "请从原局部重排候选查看重排依据");
        var actor = SecurityUtils.getUser();
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        require(actor != null && actor.getId() != null && actor.getId() > 0 && tenant != null && tenant > 0
                        && Objects.equals(actor.getTenantId(), tenant) && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && factory != null && !factory.isBlank(),
                "重排依据须在实名租户工厂内读取");
        var apsCase = cases.detail(caseId);
        require("EXECUTION_DEVIATION_REPLAN".equals(apsCase.getCaseType())
                        && Objects.equals(apsCase.getFactoryCode(), factory), "当前案件不是原执行偏差局部重排");
        var primary = Objects.requireNonNullElse(apsCase.getSources(), List.<PlnApsCaseDTO.Source>of())
                .stream().filter(item -> "PRIMARY".equals(item.getSourceRole())).toList();
        require(primary.size() == 1 && "EXECUTION_DEVIATION_CASE".equals(primary.get(0).getSourceType()),
                "当前案件缺少唯一的原执行偏差候选来源");
        var view = workbench.detail(caseId); // 先核原领域权限，再定位候选；不能凭外来候选ID读取快照。
        require("RESOURCE".equals(view.getDomain()) && "EXECUTION_DEVIATION_CASE".equals(view.getSourceType())
                        && view.getCandidates().stream().anyMatch(row -> candidateId.toString().equals(row.getId()))
                        && view.getCandidates().stream().anyMatch(row -> primary.get(0).getSourceId().equals(row.getId())),
                "候选不属于原统一案件的执行偏差情景");
        var candidate = resources.detail(candidateId);
        require(Objects.equals(candidate.getTenantId(), tenant) && Objects.equals(candidate.getFactoryCode(), factory)
                        && "EXECUTION".equals(candidate.getResourceType())
                        && "PLAN_MES_EFFECT".equals(candidate.getEvidenceSourceType())
                        && candidate.getScenarioId() != null && candidate.getScenarioId().toString().equals(view.getScenarioId()),
                "原候选租户、工厂或情景关系不一致");
        var snapshot = snapshot(candidate.getResourceEvidenceSnapshot());
        String issueId = id(snapshot.get("issueId"));
        String sourceScenarioId = id(snapshot.get("sourceScenarioId"));
        String issueNo = requiredText(snapshot, "issueNo");
        String fingerprint = requiredText(snapshot, "effectFingerprint");
        require(candidate.getIssueId() != null && candidate.getIssueId().toString().equals(issueId)
                        && Objects.equals(candidate.getIssueNo(), issueNo) && fingerprint.matches("[a-f0-9]{64}")
                        && fingerprint.equals(candidate.getResourceEvidenceFingerprint()),
                "原评估快照的计划或效果指纹无法核对");
        String sourceScenarioNo = requiredText(snapshot, "sourceScenarioNo");
        require(Objects.equals(candidate.getEvidenceSourceNo(), sourceScenarioNo), "原评估来源情景编号无法核对");
        String capturedAt = time(requiredText(snapshot, "capturedTime"));
        boolean nodes = snapshot.has("directSourceRouteNodeCodes");
        Set<String> direct = codes(snapshot.get(nodes ? "directSourceRouteNodeCodes" : "directProcessCodes"));
        JsonNode dispositions = snapshot.get("dispositions");
        require(dispositions != null && dispositions.isArray() && !dispositions.isEmpty(), "原评估没有保存偏差明细");
        List<Deviation> rows = new ArrayList<>();
        Set<String> matched = new LinkedHashSet<>();
        String identityMode = nodes ? "FROZEN_NODE" : "LEGACY_PROCESS";
        String formula = optionalText(snapshot, "businessFormula");
        require(formula == null || "MPS_REMAINING_V1".equals(formula), "原重排依据合同版本暂不支持");
        if ("MPS_REMAINING_V1".equals(formula)) {
            rows.addAll(mpsRows(snapshot, direct, nodes, tenant, factory, sourceScenarioId, issueId, issueNo, fingerprint));
        } else {
            boolean legacyBridge = nodes && java.util.stream.StreamSupport.stream(dispositions.spliterator(), false)
                    .allMatch(row -> row.path("sourceRouteNodeCode").isMissingNode() || row.path("sourceRouteNodeCode").isNull());
            Map<String, String> legacyNodes = legacyBridge ? legacyNodes(candidate, direct, tenant, factory) : Map.of();
            if (legacyBridge) identityMode = "LEGACY_PROCESS";
            for (JsonNode row : dispositions) {
                // 原快照的所有明细均须属于同一计划，不能静默跳过异计划数据。
                require(row.isObject() && issueId.equals(id(row.get("issueId"))) && issueNo.equals(requiredText(row, "issueNo"))
                                && "LOCAL_REPLAN_CANDIDATE".equals(requiredText(row, "disposition"))
                                && row.path("replanEligible").isBoolean() && row.path("replanEligible").booleanValue(),
                        "原偏差明细与计划或可评估范围不一致");
                String process = requiredText(row, "processCode");
                String node = optionalText(row, "sourceRouteNodeCode");
                String key = legacyBridge ? legacyNodes.get(process) : nodes ? node : process;
                if (!direct.contains(key)) continue; // 显式节点选择只展示当次选中的直接影响范围。
                require(matched.add(key) || !nodes, "原偏差节点明细重复，无法核对");
                rows.add(new Deviation(node, process, optionalText(row, "processName"), optionalText(row, "mesTaskCode"),
                        optionalText(row, "mesProduceTaskCode"), minutes(row, "pendingStartOverdueMinutes"),
                        minutes(row, "startDeviationMinutes"), minutes(row, "finishDeviationMinutes"), optionalText(row, "reason")));
            }
            require(matched.equals(direct), "原评估直接影响范围与偏差明细不完整");
        }
        int from = (int) Math.min(rows.size(), (current - 1L) * PAGE_SIZE);
        int to = Math.min(rows.size(), from + PAGE_SIZE);
        return new Origin("APS_DEVIATION_ORIGIN_V1", tenant.toString(), factory, caseId.toString(), candidateId.toString(),
                candidate.getCaseNo(), candidate.getStatus(), issueId, issueNo, sourceScenarioId, sourceScenarioNo,
                candidate.getScenarioId().toString(), fingerprint, capturedAt, candidate.getApplicantCode(),
                candidate.getApplicantName(), iso(candidate.getProposedTime()), candidate.getReason(), iso(candidate.getFreezeUntil()),
                identityMode, direct.size(), current, PAGE_SIZE, rows.size(), List.copyOf(rows.subList(from, to)));
    }

    /**
     * MPS摘要未携带节点；只从当时冻结的完整证明读取节点，强核原字节指纹与整个组织/计划信封。
     * 原证明没有保存的偏差分钟与工序任务编号保留未知，不按当前时刻重新计算。
     */
    private List<Deviation> mpsRows(JsonNode snapshot, Set<String> direct, boolean nodes, Long tenant, String factory,
                                    String scenarioId, String issueId, String issueNo, String fingerprint) {
        String raw = requiredText(snapshot, "fullExecutionSnapshot");
        require(sha256(raw).equals(fingerprint), "原MPS完整证明指纹不匹配");
        JsonNode proof = tree(raw);
        require(proof.isArray() && proof.size() == 13
                        && (nodes ? "MPS_REMAINING_NODE_V2" : "MPS_REMAINING_V1").equals(proof.path(0).asText())
                        && tenant.toString().equals(id(proof.path(1))) && factory.equals(proof.path(2).asText())
                        && scenarioId.equals(id(proof.path(3))) && proof.path(6).isArray()
                        && issueId.equals(id(proof.path(6).path(0))) && issueNo.equals(proof.path(6).path(1).asText())
                        && proof.path(11).isArray() && proof.path(12).isArray() && proof.path(12).isEmpty(),
                "原MPS完整证明的范围或合同不一致");
        List<Deviation> result = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(), matched = new LinkedHashSet<>();
        for (JsonNode row : proof.path(11)) {
            String process = requiredText(row, "processCode");
            String node = optionalText(row, "sourceRouteNodeCode");
            String key = nodes ? node : process;
            require(key != null && !key.isBlank() && keys.add(key), "原MPS冻结工序身份缺失或重复");
            if (!direct.contains(key)) continue;
            require(row.path("eligible").isBoolean() && row.path("eligible").booleanValue()
                            && "LOCAL_REPLAN_CANDIDATE".equals(requiredText(row, "disposition")),
                    "原MPS直接影响节点不属于当时可评估范围");
            matched.add(key);
            result.add(new Deviation(node, process, optionalText(row, "processName"), optionalText(row, "taskCode"),
                    null, null, null, null, optionalText(row, "reason")));
        }
        require(matched.equals(direct), "原MPS直接影响节点不完整");
        return result;
    }

    /**
     * 旧效果摘要只认识主工序。仅用同一候选冻结的原排程证明唯一桥接，展示仍不补填观察节点。
     * 同主工序对应多个原节点或同节点重复时停止，不能扩大当次选中的直接范围。
     */
    private Map<String, String> legacyNodes(PlnResourceReplanCaseEntity candidate, Set<String> direct,
                                           Long tenant, String factory) {
        JsonNode original = tree(candidate.getOriginalScheduleSnapshot());
        require(original.isArray() && !original.isEmpty() && candidate.getOriginalTaskId() != null,
                "旧偏差缺少冻结原排程，无法核对直接范围");
        Map<String, String> nodes = new LinkedHashMap<>();
        Map<String, Integer> processCounts = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode row : original) {
            require(tenant.toString().equals(id(row.get("tenantId"))) && factory.equals(requiredText(row, "factoryCode"))
                            && candidate.getIssueId().toString().equals(id(row.get("issueId")))
                            && candidate.getOriginalTaskId().toString().equals(id(row.get("taskId"))) && ids.add(id(row.get("id"))),
                    "旧偏差原排程与原计划范围不一致");
            String process = requiredText(row, "processCode");
            String node = requiredText(row, "sourceRouteNodeCode");
            processCounts.merge(process, 1, Integer::sum);
            require(nodes.putIfAbsent(node, process) == null, "旧偏差原排程节点重复");
        }
        Map<String, String> bridge = new LinkedHashMap<>();
        for (String node : direct) {
            String process = nodes.get(node);
            require(process != null && processCounts.get(process) == 1 && bridge.putIfAbsent(process, node) == null,
                    "旧偏差主工序不能唯一对应所选冻结节点");
        }
        return bridge;
    }

    private JsonNode tree(String raw) {
        require(raw != null && !raw.isBlank(), "历史候选未保存完整的重排依据，请查看原业务记录");
        try {
            JsonNode value = json.readTree(raw);
            require(value != null, "原重排依据为空");
            return value;
        } catch (java.io.IOException ex) {
            throw new CheckedException("原重排依据格式无法读取，请核对原业务记录");
        }
    }

    private JsonNode snapshot(String raw) {
        JsonNode value = tree(raw);
        require(value.isObject(), "原重排依据不是有效对象");
        return value;
    }

    /** JSON整数字面量只在Java中转Long再转文本，不经浏览器Number。 */
    private String id(JsonNode value) {
        require(value != null && (value.isTextual() || value.isIntegralNumber()), "原依据缺少准确主键");
        String text = value.asText();
        try {
            require(text.matches("[1-9][0-9]{0,18}") && Long.parseLong(text) > 0, "原依据主键无效");
            return text;
        } catch (NumberFormatException ex) {
            throw new CheckedException("原依据主键超出范围");
        }
    }

    private Set<String> codes(JsonNode rows) {
        require(rows != null && rows.isArray() && !rows.isEmpty(), "原评估未保存直接影响范围");
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            require(row.isTextual() && !row.textValue().isBlank() && values.add(row.textValue()), "原评估影响范围缺失或重复");
        }
        return values;
    }

    private String requiredText(JsonNode row, String key) {
        String value = optionalText(row, key);
        require(value != null && !value.isBlank(), "原评估缺少" + key + "，请核对原业务记录");
        return value;
    }

    private String optionalText(JsonNode row, String key) {
        JsonNode value = row.get(key);
        if (value == null || value.isNull()) return null;
        require(value.isTextual(), "原评估字段格式无法核对");
        return value.textValue();
    }

    private String minutes(JsonNode row, String key) {
        JsonNode value = row.get(key);
        if (value == null || value.isNull()) return null;
        require(value.isIntegralNumber() || value.isTextual(), "原偏差分钟数无法核对");
        String text = value.asText();
        try {
            require(text.matches("-?(0|[1-9][0-9]{0,18})"), "原偏差分钟数无法核对");
            return Long.toString(Long.parseLong(text));
        } catch (NumberFormatException ex) {
            throw new CheckedException("原偏差分钟数超出范围");
        }
    }

    private String time(String value) {
        try {
            return LocalDateTime.parse(value.replace(' ', 'T')).toString();
        } catch (java.time.DateTimeException ex) {
            throw new CheckedException("原评估时刻无法核对");
        }
    }

    private String iso(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new CheckedException("原MPS证明指纹无法核验");
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }

    /** 单页直接受影响节点；后续移动、冻结及实际执行仍看原工序差异/执行证据。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Deviation(String routeNodeCode, String processCode, String processName, String mesTaskCode,
                            String produceTaskCode, String pendingStartOverdueMinutes, String startDeviationMinutes,
                            String finishDeviationMinutes, String reason) {
    }

    /** 原依据与当前候选状态明确分开；不返回完整资源、MES或模型快照。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Origin(String version, String tenantId, String factoryCode, String caseId, String candidateId,
                         String candidateNo, String candidateStatus, String issueId, String issueNo,
                         String sourceScenarioId, String sourceScenarioNo, String replanScenarioId,
                         String effectFingerprint, String capturedAt, String applicantCode, String applicantName,
                         String proposedAt, String reason, String freezeUntil, String identityMode, int directCount,
                         int current, int pageSize, int total, List<Deviation> records) {
    }
}
