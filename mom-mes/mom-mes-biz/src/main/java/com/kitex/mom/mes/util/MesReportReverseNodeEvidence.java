package com.kitex.mom.mes.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesReportReverseRequestDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesReportReverseRequestEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 普通节点冲销的独立证据协议。不扩写旧每步V2事实或动态绑定永久协议。
 * 节点和实际ID均取自原冻结任务；审批展示只读取原申请正文，禁止借当前工程补造历史。
 */
public final class MesReportReverseNodeEvidence {
    public static final String NODE_VERSION = "MES_REPORT_REVERSE_CHAIN_NODE_V4";
    public static final String LEGACY_VERSION = "MES_REPORT_REVERSE_CHAIN_V2";
    public static final String BINDING_VERSION = "MES_REPORT_REVERSE_CHAIN_BINDING_V3";
    public static final String BINDING_NODE_VERSION = "MES_REPORT_REVERSE_CHAIN_BINDING_NODE_V5";
    private static final Set<String> SCOPE_FIELDS = Set.of("reportId", "arrangeId", "arrangeCode",
            "produceTaskCode", "routeCode", "sourceRouteNodeCode");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private MesReportReverseNodeEvidence() {
    }

    /** 完整图的确定性表述；ID用文本，避免前端长整型精度损失。 */
    public static List<Map<String, String>> executionNodes(List<MesProduceTaskEntity> tasks) {
        return tasks.stream().sorted(Comparator.comparing(MesProduceTaskEntity::getCode)).map(task -> {
            Map<String, String> node = new LinkedHashMap<>();
            node.put("produceTaskId", task.getId().toString());
            node.put("produceTaskCode", task.getCode());
            node.put("routeCode", task.getRouteCode());
            node.put("sourceRouteNodeCode", task.getSourceRouteNodeCode());
            node.put("predecessorNodeCodes", task.getPredecessorNodeCodes());
            node.put("successorNodeCodes", task.getSuccessorNodeCodes());
            return node;
        }).toList();
    }

    /** 原报告必须真实归属派工，不能只因主工序相同就接受另一次执行。 */
    public static void assertReport(MesTaskReportEntity report, MesTaskArrangeEntity arrange) {
        require(report != null && arrange != null && report.getId() != null && arrange.getId() != null
                && Objects.equals(report.getTenantId(), arrange.getTenantId())
                && Objects.equals(report.getFactoryCode(), arrange.getFactoryCode())
                && Objects.equals(report.getTaskCode(), arrange.getTaskCode())
                && Objects.equals(report.getProduceTaskCode(), arrange.getProduceTaskCode())
                && Objects.equals(report.getArrangeCode(), arrange.getCode()), "报工与原实际派工归属不一致");
    }

    public static Map<String, String> nodeScope(MesTaskReportEntity report, MesTaskArrangeEntity arrange) {
        assertReport(report, arrange);
        Map<String, String> scope = new LinkedHashMap<>();
        scope.put("reportId", report.getId().toString());
        scope.put("arrangeId", arrange.getId().toString());
        scope.put("arrangeCode", arrange.getCode());
        scope.put("produceTaskCode", arrange.getProduceTaskCode());
        scope.put("routeCode", arrange.getRouteCode());
        scope.put("sourceRouteNodeCode", arrange.getSourceRouteNodeCode());
        return scope;
    }

    /** 仅数据库原申请决定重采协议，不接受客户端降级参数。 */
    public static String originalVersion(MesReportReverseRequestEntity request) {
        return original(request).path("version").asText();
    }

    /** 审批查看提交时节点，旧申请返回空列表，不用当前节点替历史补身份。 */
    public static List<MesReportReverseRequestDTO.NodeScope> frozenScopes(MesReportReverseRequestEntity request) {
        JsonNode original = original(request);
        if (!Set.of(NODE_VERSION, BINDING_NODE_VERSION).contains(original.path("version").asText())) {
            return List.of();
        }
        JsonNode scopes = original.get("nodeScopes");
        require(scopes != null && scopes.isArray() && !scopes.isEmpty(), "节点冲销缺少原冻结范围");
        List<MesReportReverseRequestDTO.NodeScope> result = new ArrayList<>();
        for (JsonNode scope : scopes) {
            require(scope.isObject() && scope.size() == SCOPE_FIELDS.size(), "原节点冲销范围格式无效");
            for (String field : SCOPE_FIELDS) {
                require(scope.has(field) && scope.get(field).isTextual()
                        && !scope.get(field).asText().isBlank(), "原节点冲销范围字段缺失：" + field);
            }
            result.add(new MesReportReverseRequestDTO.NodeScope()
                    .setReportId(scope.get("reportId").asText()).setArrangeId(scope.get("arrangeId").asText())
                    .setArrangeCode(scope.get("arrangeCode").asText())
                    .setProduceTaskCode(scope.get("produceTaskCode").asText())
                    .setRouteCode(scope.get("routeCode").asText())
                    .setSourceRouteNodeCode(scope.get("sourceRouteNodeCode").asText()));
        }
        return List.copyOf(result);
    }

    private static JsonNode original(MesReportReverseRequestEntity request) {
        require(request != null && request.getEvidenceSnapshot() != null
                && Objects.equals(fingerprint(request.getEvidenceSnapshot()), request.getEvidenceFingerprint()),
                "冲销申请原证据缺失或指纹不一致");
        try {
            JsonNode root = JSON.readTree(request.getEvidenceSnapshot());
            require(root != null && root.isObject() && root.path("version").isTextual()
                    && Set.of(NODE_VERSION, LEGACY_VERSION, BINDING_VERSION, BINDING_NODE_VERSION).contains(root.get("version").asText()),
                    "未知报工冲销证据协议，禁止重解释原申请");
            return root;
        } catch (CheckedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CheckedException("冲销申请原证据不是有效JSON");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    private static String fingerprint(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("生成冲销证据指纹失败", exception);
        }
    }
}
