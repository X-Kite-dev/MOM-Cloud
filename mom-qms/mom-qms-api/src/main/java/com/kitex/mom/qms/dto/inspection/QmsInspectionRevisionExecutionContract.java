package com.kitex.mom.qms.dto.inspection;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.qms.entity.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** QMS批准事实的冻结执行意图V1；调用方须先持久化原文，不代表已执行或MES准入。 */
public final class QmsInspectionRevisionExecutionContract {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Set<String> INTENT =
            Set.of(
                    "schemaVersion",
                    "requestPayload",
                    "requestFingerprint",
                    "reviewPayload",
                    "reviewFingerprint",
                    "executorId",
                    "requestedAt");
    private static final Set<String> REQUEST =
            Set.of(
                    "schemaVersion",
                    "requestId",
                    "tenantId",
                    "factoryCode",
                    "taskId",
                    "baseEventId",
                    "baseSequence",
                    "baseFingerprint",
                    "requestedBy",
                    "requestedAt",
                    "source",
                    "targetResult",
                    "targetUsable",
                    "targetHandled",
                    "reason");
    private static final Set<String> REVIEW =
            Set.of(
                    "schemaVersion",
                    "requestId",
                    "tenantId",
                    "factoryCode",
                    "taskId",
                    "requestFingerprint",
                    "reviewedBy",
                    "reviewedAt",
                    "decision",
                    "note");
    private static final Set<String> SOURCE =
            Set.of(
                    "sourceTaskCode",
                    "sourceRequestId",
                    "sourceRequestFingerprint",
                    "matCode",
                    "processCode",
                    "labelType",
                    "standardId",
                    "batchNo",
                    "num",
                    "inspectionNum",
                    "status",
                    "result",
                    "usableNum",
                    "handleNum",
                    "handleResult",
                    "orderNo",
                    "orderId",
                    "matBarcode",
                    "deptCode",
                    "latestInspectionTime",
                    "latestAppendTime");

    private QmsInspectionRevisionExecutionContract() {}

    public static String freeze(
            QmsInspectionRevisionEntity request,
            QmsInspectionRevisionReviewEntity review,
            Long executor,
            Date when) {
        require(
                request != null
                        && review != null
                        && executor != null
                        && executor > 0
                        && when != null,
                "执行意图缺少真实申请、审核或申请人时间");
        var proof = new TreeMap<String, Object>();
        proof.put("schemaVersion", 1);
        proof.put("requestId", Objects.toString(review.getId(), null));
        proof.put("tenantId", Objects.toString(review.getTenantId(), null));
        proof.put("factoryCode", review.getFactoryCode());
        proof.put("taskId", Objects.toString(review.getTaskId(), null));
        proof.put("requestFingerprint", review.getRequestFingerprint());
        proof.put("reviewedBy", Objects.toString(review.getReviewedBy(), null));
        proof.put(
                "reviewedAt",
                review.getReviewedAt() == null
                        ? null
                        : review.getReviewedAt().toInstant().toString());
        proof.put("decision", review.getDecision());
        proof.put("note", review.getNote());
        String reviewRaw = write(proof);
        var body = new TreeMap<String, Object>();
        body.put("schemaVersion", 1);
        body.put("requestPayload", request.getPayload());
        body.put("requestFingerprint", request.getFingerprint());
        body.put("reviewPayload", reviewRaw);
        body.put("reviewFingerprint", fingerprint(reviewRaw));
        body.put("executorId", executor.toString());
        body.put("requestedAt", when.toInstant().toString());
        String raw = write(body);
        var parsed = parse(raw);
        var j = parsed.request();
        require(
                Objects.equals(request.getId(), id(j, "requestId"))
                        && Objects.equals(request.getTenantId(), id(j, "tenantId"))
                        && Objects.equals(request.getFactoryCode(), text(j, "factoryCode"))
                        && Objects.equals(request.getTaskId(), id(j, "taskId"))
                        && Objects.equals(request.getBaseEventId(), id(j, "baseEventId"))
                        && Objects.equals(request.getBaseSequence(), integer(j, "baseSequence"))
                        && Objects.equals(request.getBaseFingerprint(), text(j, "baseFingerprint"))
                        && Objects.equals(request.getRequestedBy(), id(j, "requestedBy"))
                        && request.getRequestedAt() != null
                        && request.getRequestedAt().toInstant().equals(time(j, "requestedAt"))
                        && Objects.equals(request.getTargetResult(), text(j, "targetResult"))
                        && Objects.equals(request.getTargetUsable(), qty(j, "targetUsable", false))
                        && Objects.equals(
                                request.getTargetHandled(), qty(j, "targetHandled", false))
                        && Objects.equals(request.getReason(), text(j, "reason")),
                "执行意图与实际申请索引不一致");
        return raw;
    }

    public static Parsed parse(String raw) {
        var j = tree(raw, 131072);
        fields(j, INTENT);
        version(j);
        String requestRaw = text(j, "requestPayload"), reviewRaw = text(j, "reviewPayload");
        require(
                fingerprint(requestRaw).equals(sha(j, "requestFingerprint"))
                        && fingerprint(reviewRaw).equals(sha(j, "reviewFingerprint")),
                "执行意图的申请或审核原文摘要不一致");
        var request = tree(requestRaw, 65536);
        fields(request, REQUEST);
        version(request);
        var review = tree(reviewRaw, 8192);
        fields(review, REVIEW);
        version(review);
        for (String key : List.of("requestId", "tenantId", "taskId"))
            require(id(request, key).equals(id(review, key)), "执行意图的审核与申请身份不一致");
        require(
                text(request, "factoryCode").equals(text(review, "factoryCode"))
                        && text(request, "factoryCode").length() <= 100
                        && sha(review, "requestFingerprint").equals(sha(j, "requestFingerprint"))
                        && "APPROVED".equals(text(review, "decision")),
                "执行意图缺少当前组织一致的批准原件");
        require(!id(request, "requestedBy").equals(id(review, "reviewedBy")), "执行意图不接受本人审核");
        Instant requested = time(request, "requestedAt"),
                reviewed = time(review, "reviewedAt"),
                when = time(j, "requestedAt");
        require(!reviewed.isBefore(requested) && !when.isBefore(reviewed), "执行意图的申请、审核和提交时间倒序");
        id(request, "baseEventId");
        sha(request, "baseFingerprint");
        long seq = integer(request, "baseSequence");
        require(seq > 0 && seq <= 5000, "修订原件序号无效");
        note(request, "reason");
        note(review, "note");
        var source = request.get("source");
        fields(source, SOURCE);
        require(
                text(source, "sourceRequestId")
                        .startsWith(InspectionProcedureSourceContract.REQUEST_PREFIX),
                "执行意图缺少普通MES来源");
        sha(source, "sourceRequestFingerprint");
        for (String name :
                List.of(
                        "sourceTaskCode",
                        "matCode",
                        "processCode",
                        "orderNo",
                        "labelType",
                        "status",
                        "result")) text(source, name);
        require(
                Set.of("QUALIFIED", "UN_QUALIFIED").contains(text(source, "result"))
                        && Set.of("FINISHED", "UNHANDLED").contains(text(source, "status")),
                "执行意图原判定不适用");
        int total = qty(source, "num", false);
        require(total > 0, "执行意图检验批量无效");
        for (String name : List.of("inspectionNum", "usableNum", "handleNum")) {
            Integer n = qty(source, name, true);
            require(n == null || n <= total, "执行意图来源数量超出检验批量");
        }
        if (!source.get("standardId").isNull()) id(source, "standardId");
        for (String name : List.of("batchNo", "handleResult", "orderId", "matBarcode", "deptCode"))
            require(source.get(name).isNull() || source.get(name).isTextual(), "执行意图可空文本类型不正确");
        for (String name : List.of("latestInspectionTime", "latestAppendTime")) {
            var v = source.get(name);
            require(
                    v.isNull() || v.isTextual() || (v.isIntegralNumber() && v.canConvertToLong()),
                    "执行意图来源时间类型不正确");
        }
        String target = text(request, "targetResult");
        require(
                Set.of("QUALIFIED", "UN_QUALIFIED").contains(target)
                        && !target.equals(text(source, "result")),
                "执行意图目标必须是明确的不同判定");
        int n = "QUALIFIED".equals(target) ? total : 0;
        require(
                qty(request, "targetUsable", false) == n
                        && qty(request, "targetHandled", false) == n,
                "执行意图目标不是明确整批数量");
        return new Parsed(request, review, id(j, "executorId"), when, requestRaw, reviewRaw);
    }

    public record Parsed(
            JsonNode request,
            JsonNode review,
            Long executorId,
            Instant requestedAt,
            String requestPayload,
            String reviewPayload) {}

    public static String fingerprint(String raw) {
        require(raw != null, "缺少执行原文");
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode tree(String raw, int bound) {
        require(
                raw != null
                        && raw.length() <= bound
                        && raw.getBytes(StandardCharsets.UTF_8).length <= bound,
                "执行意图原文超过容量或缺失");
        try {
            var j = JSON.readTree(raw);
            require(j != null && j.isObject(), "执行意图须为完整对象");
            return j;
        } catch (CheckedException e) {
            throw e;
        } catch (Exception e) {
            throw new CheckedException("执行意图原文不能严格解析", e);
        }
    }

    private static void fields(JsonNode j, Set<String> expected) {
        require(j != null && j.isObject(), "执行意图缺少完整对象");
        var names = new HashSet<String>();
        j.fieldNames().forEachRemaining(names::add);
        require(names.equals(expected), "执行意图字段缺失或含未知字段");
    }

    private static void version(JsonNode j) {
        require(
                j.path("schemaVersion").isInt() && j.get("schemaVersion").intValue() == 1,
                "执行意图版本不支持");
    }

    private static String text(JsonNode j, String key) {
        var n = j.get(key);
        require(n != null && n.isTextual() && !n.textValue().isBlank(), "执行意图缺少明确文本 " + key);
        return n.textValue();
    }

    private static Long id(JsonNode j, String key) {
        String s = text(j, key);
        require(s.matches("[1-9][0-9]{0,18}"), "执行意图主键必须为正数文本 " + key);
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            throw new CheckedException("执行意图主键超出范围 " + key);
        }
    }

    private static long integer(JsonNode j, String key) {
        var n = j.get(key);
        require(n != null && n.isIntegralNumber() && n.canConvertToLong(), "执行意图整数类型不正确 " + key);
        return n.longValue();
    }

    private static Integer qty(JsonNode j, String key, boolean nullable) {
        var n = j.get(key);
        require(n != null, "执行意图缺少可空数量 " + key);
        if (nullable && n.isNull()) return null;
        require(
                n.isIntegralNumber() && n.canConvertToInt() && n.intValue() >= 0,
                "执行意图数量类型不正确 " + key);
        return n.intValue();
    }

    private static Instant time(JsonNode j, String key) {
        try {
            Instant value = Instant.parse(text(j, key));
            require(value.getNano() % 1_000_000 == 0, "执行意图时间精度须为毫秒");
            return value;
        } catch (CheckedException e) {
            throw e;
        } catch (Exception e) {
            throw new CheckedException("执行意图时间须为明确UTC偏移的原始时间 " + key, e);
        }
    }

    private static String sha(JsonNode j, String key) {
        String s = text(j, key);
        require(s.matches("[0-9a-f]{64}"), "执行意图摘要无效 " + key);
        return s;
    }

    private static void note(JsonNode j, String key) {
        require(text(j, key).length() <= 1000, "执行依据超过1000字符");
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new CheckedException("执行意图不能冻结", e);
        }
    }

    private static void require(boolean c, String m) {
        if (!c) throw new CheckedException(m);
    }
}
