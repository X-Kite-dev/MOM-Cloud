package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;

import java.math.BigDecimal;
import java.util.*;

/**
 * Frozen PLAN business callbacks. Rework proof uses actual complete source facts, not current
 * reconstruction.
 */
final class MesSourcePlanPayload {
    private MesSourcePlanPayload() {}

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .setNodeFactory(
                            com.fasterxml.jackson.databind.node.JsonNodeFactory
                                    .withExactBigDecimals(true));

    static String encode(MesTaskEntity task, String request, List<OmsOrderLifeCycleDTO> callbacks) {
        var original = new TreeMap<String, Object>();
        original.put("schemaVersion", 1);
        original.put("tenantId", task.getTenantId().toString());
        original.put("factoryCode", task.getFactoryCode());
        original.put("taskId", task.getId().toString());
        original.put("taskCode", task.getCode());
        original.put("requestId", request);
        original.put("callbacks", callbacks);
        try {
            return JSON.writeValueAsString(original);
        } catch (Exception e) {
            throw new CheckedException("PLAN来源通知无法冻结", e);
        }
    }

    static List<OmsOrderLifeCycleDTO> rework(
            MesSourceQuantityPlanOutboxEntity row,
            MesTaskEntity source,
            List<MesApsSourceQuantityEventEntity> facts,
            MesTaskEntity rework)
            throws Exception {
        require(
                row != null
                        && source != null
                        && facts != null
                        && !facts.isEmpty()
                        && facts.size() <= 5000
                        && rework != null,
                "返工PLAN原件缺少实际来源分录或原工单");
        require(
                Objects.equals(source.getId(), row.getTaskId())
                        && Objects.equals(source.getCode(), row.getTaskCode())
                        && Objects.equals(source.getTenantId(), row.getTenantId())
                        && Objects.equals(source.getFactoryCode(), row.getFactoryCode())
                        && "REWORK".equals(rework.getResourceType())
                        && "FINISH".equals(rework.getStatus())
                        && Objects.equals(rework.getTenantId(), row.getTenantId())
                        && Objects.equals(rework.getFactoryCode(), row.getFactoryCode())
                        && Objects.equals(rework.getSourceTaskCode(), source.getCode())
                        && Objects.equals(
                                row.getRequestId(),
                                "REWORK-COMPLETED:" + rework.getReworkRequestId()),
                "返工PLAN原件实际来源或永久请求不一致");
        JsonNode body = JSON.readTree(row.getPayload());
        require(
                body != null
                        && body.isObject()
                        && body.size() == 7
                        && body.path("schemaVersion").isIntegralNumber()
                        && body.path("schemaVersion").asInt() == 1
                        && body.path("tenantId").isTextual()
                        && body.path("tenantId").asText().equals(row.getTenantId().toString())
                        && body.path("factoryCode").asText().equals(row.getFactoryCode())
                        && body.path("taskId").isTextual()
                        && body.path("taskId").asText().equals(row.getTaskId().toString())
                        && body.path("taskCode").asText().equals(row.getTaskCode())
                        && body.path("requestId").asText().equals(row.getRequestId())
                        && body.path("callbacks").isArray(),
                "返工PLAN原件信封身份无效");
        List<OmsOrderLifeCycleDTO> callbacks =
                JSON.readerForListOf(OmsOrderLifeCycleDTO.class).readValue(body.path("callbacks"));
        require(
                callbacks.size() == facts.size()
                        && encode(source, row.getRequestId(), callbacks).equals(row.getPayload()),
                "返工PLAN原件非完整确定性原文");
        Map<String, MesApsSourceQuantityEventEntity> byRequest = new HashMap<>();
        Set<Long> ids = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (var fact : facts) {
            require(
                    fact != null
                            && fact.getId() != null
                            && fact.getId() > 0
                            && ids.add(fact.getId())
                            && Objects.equals(fact.getTenantId(), row.getTenantId())
                            && Objects.equals(fact.getFactoryCode(), row.getFactoryCode())
                            && Objects.equals(fact.getRequestId(), row.getRequestId())
                            && Objects.equals(
                                    fact.getRequestFingerprint(), row.getRequestFingerprint())
                            && Objects.equals(fact.getMesTaskId(), row.getTaskId())
                            && Objects.equals(fact.getMesTaskCode(), row.getTaskCode())
                            && "REWORK_COMPLETED".equals(fact.getEventKind())
                            && Objects.equals(fact.getSourceReference(), rework.getCode())
                            && fact.getQuantityDelta() != null
                            && fact.getQuantityDelta().signum() > 0
                            && fact.getSourceIssueNo() != null
                            && !fact.getSourceIssueNo().isBlank()
                            && (fact.getSourceIssueId() == null || fact.getSourceIssueId() > 0),
                    "返工PLAN实际来源分录无效");
            String key =
                    "MES-SOURCE-QTY:"
                            + row.getRequestId()
                            + ":"
                            + Objects.requireNonNullElse(
                                    fact.getSourceIssueId(), fact.getSourceIssueNo());
            if (key.length() > 200)
                key = "MES-SOURCE-QTY:SHA256:" + MesInspectionEventProjection.hash(key);
            require(byRequest.put(key, fact) == null, "返工PLAN逐来源请求重复");
            sum = sum.add(fact.getQuantityDelta());
        }
        require(
                rework.getCompleteNum() != null && rework.getCompleteNum().compareTo(sum) == 0,
                "返工PLAN逐来源量与实际完工不守恒");
        for (var callback : callbacks) {
            require(callback != null, "返工PLAN来源回调缺失");
            var fact = byRequest.remove(callback.getRequestId());
            require(fact != null, "返工PLAN来源回调重复或不属于原请求");
            var expected = new OmsOrderLifeCycleDTO();
            expected.setRequestId(callback.getRequestId());
            expected.setNo(fact.getSourceIssueNo());
            expected.setProductCode(source.getMatCode());
            expected.setType("mes");
            expected.setContent(callback.getContent());
            expected.setReworkCompletedNum(fact.getQuantityDelta());
            require(
                    JSON.valueToTree(expected).equals(JSON.valueToTree(callback)),
                    "返工PLAN回调来源、数量或类别与实际原件不一致");
        }
        require(byRequest.isEmpty(), "返工PLAN原件漏掉实际来源");
        return callbacks;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }
}
