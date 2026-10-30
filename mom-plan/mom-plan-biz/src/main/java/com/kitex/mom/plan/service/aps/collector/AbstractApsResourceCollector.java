package com.kitex.mom.plan.service.aps.collector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.exception.CheckedException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * APS 采集器的规范序列化与故障关闭公共能力。
 */
abstract class AbstractApsResourceCollector implements PlnApsResourceCollector {

    protected final ObjectMapper objectMapper;

    protected AbstractApsResourceCollector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    protected PlnApsResourceEvidence evidence(boolean critical, String status, String version,
                                              LocalDateTime observedAt, Map<String, Object> summary,
                                              List<String> refs, List<PlnApsResourceEvidence.Gap> gaps) {
        String fingerprint = stableFingerprint(Map.of(
                "module", module(), "status", status, "version", version,
                "summary", summary, "refs", refs, "gaps", gaps));
        return new PlnApsResourceEvidence(module(), status, critical, version, observedAt,
                summary, List.copyOf(refs), List.copyOf(gaps), fingerprint);
    }

    protected PlnApsResourceEvidence unavailable(boolean critical, String code, String category,
                                                 String sourceType, String title, Exception error,
                                                 String route) {
        String description = error == null ? title : title + "：" + safeMessage(error);
        String severity = critical ? "BLOCKER" : "WARNING";
        var gap = new PlnApsResourceEvidence.Gap(code, category, severity, sourceType, null,
                title, description, route, Map.of());
        return evidence(critical, "UNAVAILABLE", "UNAVAILABLE", LocalDateTime.now(),
                Map.of("available", false), List.of(), List.of(gap));
    }

    protected String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new CheckedException("APS资源证据无法规范序列化");
        }
    }

    protected String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少SHA-256", e);
        }
    }

    /**
     * 采集时刻只用于审计展示，不得导致业务事实未变化却误判快照失效。
     */
    protected String stableFingerprint(Object value) {
        JsonNode tree = objectMapper.valueToTree(value);
        stripVolatileTime(tree);
        return sha256(tree.toString());
    }

    private void stripVolatileTime(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove(List.of("observedAt", "capturedAt"));
            object.elements().forEachRemaining(this::stripVolatileTime);
        } else if (node instanceof ArrayNode array) {
            array.elements().forEachRemaining(this::stripVolatileTime);
        }
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
