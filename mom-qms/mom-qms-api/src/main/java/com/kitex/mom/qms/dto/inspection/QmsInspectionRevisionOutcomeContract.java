package com.kitex.mom.qms.dto.inspection;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** 正式改判/中止的互斥原件；JSON只包含冻结事实，不提供跳过门禁标志。 */
public final class QmsInspectionRevisionOutcomeContract {
    private QmsInspectionRevisionOutcomeContract() {}

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Set<String> FIELDS =
            Set.of(
                    "schemaVersion",
                    "decision",
                    "requestId",
                    "tenantId",
                    "factoryCode",
                    "taskId",
                    "intentFingerprint",
                    "executorId",
                    "sealFingerprint",
                    "occurredAt",
                    "eventPayload",
                    "eventFingerprint",
                    "abortBy",
                    "reason");

    public record Parsed(
            String decision,
            String sealFingerprint,
            Instant occurredAt,
            String eventPayload,
            Long abortBy,
            String reason,
            JsonNode event) {}

    public static String committed(String intent, String seal, String event, Date when) {
        return build(intent, seal, event, null, null, when);
    }

    public static String aborted(String intent, String seal, Long actor, String reason, Date when) {
        check(
                actor != null
                        && actor > 0
                        && reason != null
                        && !reason.isBlank()
                        && reason.strip().length() <= 1000,
                "中止人/原因无效");
        return build(intent, seal, null, actor, reason.strip(), when);
    }

    private static String build(
            String intent, String seal, String event, Long abort, String reason, Date when) {
        var p = QmsInspectionRevisionExecutionContract.parse(intent);
        var q = p.request();
        check(when != null, "正式结果缺少时间");
        Map<String, Object> data = new TreeMap<>();
        data.put("schemaVersion", 1);
        data.put("decision", abort == null ? "COMMITTED" : "ABORTED");
        for (String n : List.of("requestId", "tenantId", "factoryCode", "taskId"))
            data.put(n, q.path(n).textValue());
        data.put("intentFingerprint", hash(intent));
        data.put("executorId", p.executorId().toString());
        data.put("sealFingerprint", seal);
        data.put("occurredAt", when.toInstant().toString());
        data.put("eventPayload", event);
        data.put("eventFingerprint", event == null ? null : hash(event));
        data.put("abortBy", abort == null ? null : abort.toString());
        data.put("reason", abort == null ? q.path("reason").textValue() : reason);
        String raw = write(data);
        parse(raw, intent);
        return raw;
    }

    public static Parsed parse(String raw, String intent) {
        try {
            check(
                    raw != null && raw.getBytes(StandardCharsets.UTF_8).length <= 131072,
                    "正式结果原件超过容量");
            var j = JSON.readTree(raw);
            var names = new HashSet<String>();
            j.fieldNames().forEachRemaining(names::add);
            check(
                    j.isObject()
                            && names.equals(FIELDS)
                            && j.path("schemaVersion").isInt()
                            && j.path("schemaVersion").intValue() == 1,
                    "正式结果字段/版本无效");
            var p = QmsInspectionRevisionExecutionContract.parse(intent);
            var q = p.request();
            for (String n : List.of("requestId", "tenantId", "factoryCode", "taskId"))
                check(j.path(n).isTextual() && j.get(n).equals(q.get(n)), "正式结果身份不一致");
            check(
                    hash(intent).equals(text(j, "intentFingerprint"))
                            && p.executorId().toString().equals(text(j, "executorId")),
                    "正式结果指纹/执行人不一致");
            Instant time = Instant.parse(text(j, "occurredAt"));
            check(
                    time.getNano() % 1000000 == 0 && !time.isBefore(p.requestedAt()),
                    "正式结果时间早于执行意图或精度无效");
            String decision = text(j, "decision"), seal = nullable(j, "sealFingerprint");
            check(seal == null || seal.matches("[0-9a-f]{64}"), "封存摘要无效");
            String reason = text(j, "reason");
            check(!reason.isBlank() && reason.length() <= 1000, "正式结果原因无效");
            if ("ABORTED".equals(decision)) {
                check(
                        j.get("eventPayload").isNull() && j.get("eventFingerprint").isNull(),
                        "中止不得携带已提交结果");
                Long actor = id(text(j, "abortBy"));
                return new Parsed(decision, seal, time, null, actor, reason, null);
            }
            check(
                    "COMMITTED".equals(decision)
                            && seal != null
                            && j.get("abortBy").isNull()
                            && reason.equals(q.path("reason").textValue()),
                    "提交/中止原件不一致");
            String eventRaw = text(j, "eventPayload");
            check(
                    eventRaw.getBytes(StandardCharsets.UTF_8).length <= 65536
                            && hash(eventRaw).equals(text(j, "eventFingerprint")),
                    "正式RESULT原文/SHA无效");
            var e = JSON.readTree(eventRaw);
            check(
                    e.isObject()
                            && e.path("schemaVersion").asInt() == 1
                            && "QMS".equals(text(e, "sourceSystem"))
                            && "PROCEDURE".equals(text(e, "inspectionType"))
                            && "RESULT".equals(text(e, "eventType"))
                            && e.path("sequenceNo").isIntegralNumber()
                            && e.path("sequenceNo").longValue() == 3
                            && e.path("firstPassEligible").isBoolean()
                            && !e.path("firstPassEligible").booleanValue()
                            && e.path("qualified").isBoolean()
                            && !e.path("qualified").booleanValue()
                            && e.path("dataGaps").isArray(),
                    "正式修订不是连续第三件RESULT");
            id(text(e, "eventId"));
            id(text(e, "historyId"));
            for (String n : List.of("tenantId", "factoryCode", "taskId"))
                check(e.get(n).equals(q.get(n)), "RESULT身份不一致");
            check(
                    e.path("previousEventId").equals(q.get("baseEventId"))
                            && e.path("previousFingerprint").equals(q.get("baseFingerprint")),
                    "RESULT前件不一致");
            Instant occurred = Instant.parse(text(e, "occurredAt"));
            check(occurred.getNano() % 1000000 == 0 && !occurred.isAfter(time), "RESULT时间超出正式提交时间");
            JsonNode before = q.get("source"), after = e.get("source");
            check(after != null && after.isObject() && after.size() == before.size(), "正式结果缺少完整来源");
            Set<String> changed =
                    Set.of("result", "status", "usableNum", "handleNum", "latestInspectionTime");
            var fields = before.fieldNames();
            while (fields.hasNext()) {
                String n = fields.next();
                check(
                        after.has(n) && (changed.contains(n) || before.get(n).equals(after.get(n))),
                        "正式改判变更了冻结生产来源");
            }
            check(
                    "QUALIFIED".equals(before.path("result").textValue())
                            && "FIRST".equals(before.path("labelType").textValue())
                            && "UN_QUALIFIED".equals(after.path("result").textValue())
                            && "UNHANDLED".equals(after.path("status").textValue())
                            && after.path("usableNum").isIntegralNumber()
                            && after.path("usableNum").longValue() == 0
                            && after.path("handleNum").isIntegralNumber()
                            && after.path("handleNum").longValue() == 0
                            && !after.path("latestInspectionTime").isNull(),
                    "首版正式改判结果无效");
            return new Parsed(decision, seal, time, eventRaw, null, reason, e);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("正式修订原件不可核验", ex);
        }
    }

    public static String hash(String raw) {
        return QmsInspectionRevisionExecutionContract.fingerprint(raw);
    }

    public static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("正式修订原件编码失败", ex);
        }
    }

    private static String text(JsonNode n, String k) {
        check(n.has(k) && n.get(k).isTextual(), "正式结果缺少文本" + k);
        return n.get(k).textValue();
    }

    private static String nullable(JsonNode n, String k) {
        check(n.has(k), "缺少可空字段" + k);
        return n.get(k).isNull() ? null : text(n, k);
    }

    private static Long id(String s) {
        check(s.matches("[1-9][0-9]{0,18}"), "主键格式无效");
        return Long.valueOf(s);
    }

    private static void check(boolean v, String m) {
        if (!v) throw new IllegalArgumentException(m);
    }
}
