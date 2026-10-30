package com.kitex.mom.qms.dto.inspection;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.mom.qms.dto.resp.HandleFinishDTO;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** 已判不合格后的合法正向处置原件；不把处置伪装为原始合格判定。 */
public final class QmsProcedureDispositionContract {
    private QmsProcedureDispositionContract() {}

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public static final Set<String> SOURCE_FIELDS =
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
    private static final Set<String> FIELDS =
            Set.of(
                    "schemaVersion",
                    "receiptId",
                    "tenantId",
                    "factoryCode",
                    "taskId",
                    "requestId",
                    "handleRecordId",
                    "historyId",
                    "handledBy",
                    "handledByName",
                    "recordedBy",
                    "sourceOffsetSeconds",
                    "handledAt",
                    "kind",
                    "quantity",
                    "reason",
                    "authorizationRef",
                    "concessionUnrestricted",
                    "previousEventId",
                    "previousFingerprint",
                    "beforeSource");
    private static final Set<String> EVENT_FIELDS =
            Set.of(
                    "schemaVersion",
                    "sourceSystem",
                    "eventId",
                    "tenantId",
                    "factoryCode",
                    "inspectionType",
                    "taskId",
                    "historyId",
                    "eventType",
                    "sequenceNo",
                    "previousEventId",
                    "previousFingerprint",
                    "occurredAt",
                    "quantity",
                    "qualified",
                    "cancelReason",
                    "recordedBy",
                    "source",
                    "firstPassEligible",
                    "dataGaps",
                    "dispositionPayload",
                    "dispositionFingerprint");

    public record Identity(
            Long receiptId,
            Long tenantId,
            String factoryCode,
            Long taskId,
            Long recordId,
            Long historyId,
            Long actorId,
            String actorName,
            Date time,
            String recordedBy,
            int sourceOffsetSeconds) {
        public Identity(
                Long receiptId,
                Long tenantId,
                String factoryCode,
                Long taskId,
                Long recordId,
                Long historyId,
                Long actorId,
                String actorName,
                Date time) {
            this(
                    receiptId,
                    tenantId,
                    factoryCode,
                    taskId,
                    recordId,
                    historyId,
                    actorId,
                    actorName,
                    time,
                    actorId == null ? null : actorId.toString(),
                    offset(time));
        }

        public Identity(
                Long receiptId,
                Long tenantId,
                String factoryCode,
                Long taskId,
                Long recordId,
                Long historyId,
                Long actorId,
                String actorName,
                Date time,
                String recordedBy) {
            this(
                    receiptId,
                    tenantId,
                    factoryCode,
                    taskId,
                    recordId,
                    historyId,
                    actorId,
                    actorName,
                    time,
                    recordedBy,
                    offset(time));
        }

        private static int offset(Date time) {
            return time == null
                    ? 0
                    : java.time.ZoneId.systemDefault()
                            .getRules()
                            .getOffset(time.toInstant())
                            .getTotalSeconds();
        }
    }

    public record Parsed(
            JsonNode body,
            JsonNode before,
            JsonNode after,
            String requestId,
            String kind,
            int quantity,
            Long actorId,
            String reason,
            String authorizationRef,
            boolean concessionUnrestricted) {}

    public static String create(
            Identity identity,
            HandleFinishDTO command,
            Long previous,
            String previousFingerprint,
            JsonNode source) {
        check(identity != null && command != null && identity.time() != null, "处置缺少实际身份/命令/时间");
        Map<String, Object> j = new TreeMap<>();
        j.put("schemaVersion", 1);
        j.put("receiptId", str(identity.receiptId()));
        j.put("tenantId", str(identity.tenantId()));
        j.put("factoryCode", identity.factoryCode());
        j.put("taskId", str(identity.taskId()));
        j.put("requestId", strip(command.getRequestId()));
        j.put("handleRecordId", str(identity.recordId()));
        j.put("historyId", str(identity.historyId()));
        j.put("handledBy", str(identity.actorId()));
        j.put("handledByName", strip(identity.actorName()));
        j.put("recordedBy", identity.recordedBy());
        j.put("sourceOffsetSeconds", identity.sourceOffsetSeconds());
        j.put("handledAt", identity.time().toInstant().toString());
        j.put("kind", command.getHandleResult());
        j.put("quantity", command.getHandleNum());
        j.put("reason", strip(command.getHandleRemark()));
        boolean concession = "CONCESSION".equals(command.getHandleResult());
        j.put("authorizationRef", concession ? strip(command.getAuthorizationRef()) : null);
        j.put(
                "concessionUnrestricted",
                concession && Boolean.TRUE.equals(command.getConcessionUnrestricted()));
        j.put("previousEventId", str(previous));
        j.put("previousFingerprint", previousFingerprint);
        j.put("beforeSource", source);
        String raw = write(j);
        parse(raw);
        return raw;
    }

    public static Parsed parse(String raw) {
        try {
            check(
                    raw != null && raw.getBytes(StandardCharsets.UTF_8).length <= 32768,
                    "处置原件超过32KiB");
            JsonNode j = JSON.readTree(raw);
            fields(j, FIELDS);
            check(
                    j.path("schemaVersion").isInt() && j.get("schemaVersion").intValue() == 1,
                    "处置原件版本无效");
            for (String k :
                    List.of(
                            "receiptId",
                            "tenantId",
                            "taskId",
                            "handleRecordId",
                            "historyId",
                            "handledBy",
                            "previousEventId")) id(text(j, k));
            String factory = text(j, "factoryCode"),
                    request = text(j, "requestId"),
                    kind = text(j, "kind"),
                    reason = text(j, "reason"),
                    actorName = text(j, "handledByName");
            check(
                    !factory.isBlank()
                            && factory.length() <= 100
                            && request.matches("[A-Za-z0-9_-]{16,100}")
                            && Set.of("RELEASE", "CONCESSION").contains(kind)
                            && !reason.isBlank()
                            && reason.equals(reason.strip())
                            && reason.length() <= 200
                            && !actorName.isBlank()
                            && actorName.length() <= 100,
                    "处置请求/类型/人员/原因无效");
            check(text(j, "previousFingerprint").matches("[0-9a-f]{64}"), "处置前件摘要无效");
            check(
                    !text(j, "recordedBy").isBlank() && text(j, "recordedBy").length() <= 128,
                    "处置缺少实际审计账号");
            check(
                    integer(j, "sourceOffsetSeconds") >= -64800
                            && integer(j, "sourceOffsetSeconds") <= 64800,
                    "JDBC原始墙钟偏移无效");
            Instant time = Instant.parse(text(j, "handledAt"));
            check(time.toEpochMilli() > 0 && time.getNano() % 1000000 == 0, "处置时间或精度无效");
            check(j.get("concessionUnrestricted").isBoolean(), "让步范围声明必须明确");
            String authorization = nullable(j, "authorizationRef");
            boolean unrestricted = j.get("concessionUnrestricted").booleanValue();
            if ("CONCESSION".equals(kind))
                check(
                        unrestricted
                                && authorization != null
                                && !authorization.isBlank()
                                && authorization.equals(authorization.strip())
                                && authorization.length() <= 1000,
                        "让步必须具备内部无条件授权依据");
            else check(!unrestricted && authorization == null, "放行不能携带让步声明");
            JsonNode before = j.get("beforeSource");
            validateSource(before);
            int total = integer(before, "num"),
                    handled = integer(before, "handleNum"),
                    usable = integer(before, "usableNum"),
                    quantity = integer(j, "quantity");
            check(
                    total > 0
                            && quantity > 0
                            && handled >= 0
                            && handled < total
                            && usable == handled
                            && quantity <= total - handled,
                    "处置量超出真实待处置量或既有数量不明确");
            check(
                    "UN_QUALIFIED".equals(text(before, "result"))
                            && "UNHANDLED".equals(text(before, "status")),
                    "仅待处置不合格可增加可用量");
            if (!before.get("latestInspectionTime").isNull())
                check(
                        time.toEpochMilli() >= before.get("latestInspectionTime").longValue(),
                        "处置时间早于检验判定");
            Set<String> kinds = new TreeSet<>();
            String prior = nullable(before, "handleResult");
            if (handled == 0) check(prior == null || prior.isEmpty(), "原处置结果与零累计不一致");
            else {
                check(prior != null && !prior.isEmpty(), "已有处置量缺少原类型");
                kinds.addAll(Arrays.asList(prior.split(",", -1)));
                check(
                        Set.of("RELEASE", "CONCESSION").containsAll(kinds)
                                && String.join(",", kinds).equals(prior),
                        "原处置不是可核对的纯接受链");
            }
            kinds.add(kind);
            ObjectNode after = (ObjectNode) before.deepCopy();
            int next = Math.addExact(handled, quantity);
            after.put("usableNum", Math.addExact(usable, quantity))
                    .put("handleNum", next)
                    .put("handleResult", String.join(",", kinds));
            if (next == total)
                after.put("result", kinds.contains("CONCESSION") ? "RECEIVE" : "RELEASE")
                        .put("status", "FINISHED");
            return new Parsed(
                    j,
                    before,
                    after,
                    request,
                    kind,
                    quantity,
                    id(text(j, "handledBy")),
                    reason,
                    authorization,
                    unrestricted);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("处置原件不可核验", e);
        }
    }

    public static Parsed validateEvent(JsonNode event) {
        fields(event, EVENT_FIELDS);
        check(
                event.path("schemaVersion").isInt()
                        && event.get("schemaVersion").intValue() == 2
                        && "DISPOSITION".equals(text(event, "eventType"))
                        && "QMS".equals(text(event, "sourceSystem"))
                        && "PROCEDURE".equals(text(event, "inspectionType")),
                "不是普通V2工序处置事件");
        String raw = text(event, "dispositionPayload");
        check(hash(raw).equals(text(event, "dispositionFingerprint")), "处置原件SHA不一致");
        Parsed p = parse(raw);
        JsonNode j = p.body();
        id(text(event, "eventId"));
        for (String k :
                List.of(
                        "tenantId",
                        "factoryCode",
                        "taskId",
                        "historyId",
                        "previousEventId",
                        "previousFingerprint"))
            check(event.has(k) && event.get(k).equals(j.get(k)), "处置事件身份/前件与原件不一致");
        check(event.get("source").equals(p.after()), "处置目标来源与原件不一致");
        check(
                event.get("qualified").isNull() && event.get("cancelReason").isNull(),
                "处置不得伪装检验判定或作废");
        check(event.get("recordedBy").equals(j.get("recordedBy")), "处置历史操作人与原件不一致");
        check(event.get("quantity").equals(j.get("quantity")), "处置历史数量与原件不一致");
        check(event.get("occurredAt").equals(j.get("handledAt")), "处置历史时间与原件不一致");
        check(
                event.path("sequenceNo").isIntegralNumber()
                        && event.get("sequenceNo").canConvertToLong()
                        && event.get("sequenceNo").longValue() > 1
                        && event.get("sequenceNo").longValue() <= 5000
                        && event.get("firstPassEligible").isBoolean()
                        && !event.get("firstPassEligible").booleanValue()
                        && event.get("dataGaps").isArray(),
                "处置事件序号/首检资格无效");
        return p;
    }

    public static void verifyPredecessor(JsonNode event, String previousRaw) {
        Parsed p = validateEvent(event);
        try {
            JsonNode previous = JSON.readTree(previousRaw);
            check(
                    hash(previousRaw).equals(text(p.body(), "previousFingerprint"))
                            && previous.get("eventId").equals(p.body().get("previousEventId"))
                            && event.path("sequenceNo").longValue()
                                    == previous.path("sequenceNo").longValue() + 1,
                    "处置不是原连续前件的下一件");
            for (String k : List.of("tenantId", "factoryCode", "taskId"))
                check(previous.get(k).equals(event.get(k)), "处置前件越域或换单");
            check(
                    p.before().equals(previous.get("source"))
                            && !Instant.parse(text(event, "occurredAt"))
                                    .isBefore(Instant.parse(text(previous, "occurredAt"))),
                    "处置前件原文/时间已变化");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("处置前件不可核验", e);
        }
    }

    public static boolean matches(Parsed p, HandleFinishDTO c, Long actor) {
        boolean concession = "CONCESSION".equals(c.getHandleResult());
        return Objects.equals(p.requestId(), strip(c.getRequestId()))
                && Objects.equals(p.body().path("taskId").textValue(), str(c.getId()))
                && Objects.equals(p.kind(), c.getHandleResult())
                && Objects.equals(p.quantity(), c.getHandleNum())
                && Objects.equals(p.actorId(), actor)
                && Objects.equals(p.reason(), strip(c.getHandleRemark()))
                && Objects.equals(
                        p.authorizationRef(), concession ? strip(c.getAuthorizationRef()) : null)
                && p.concessionUnrestricted()
                        == (concession && Boolean.TRUE.equals(c.getConcessionUnrestricted()))
                && Objects.equals(
                        p.body().path("previousEventId").textValue(), str(c.getExpectedEventId()))
                && Objects.equals(
                        p.body().path("previousFingerprint").textValue(),
                        c.getExpectedFingerprint());
    }

    public static void validateSource(JsonNode n) {
        fields(n, SOURCE_FIELDS);
        for (String k :
                List.of(
                        "sourceTaskCode",
                        "sourceRequestId",
                        "sourceRequestFingerprint",
                        "matCode",
                        "processCode",
                        "labelType",
                        "orderNo")) check(!text(n, k).isBlank(), "处置来源缺少" + k);
        check(
                text(n, "sourceRequestId")
                                .startsWith(InspectionProcedureSourceContract.REQUEST_PREFIX)
                        && text(n, "sourceRequestFingerprint").matches("[0-9a-f]{64}")
                        && Set.of("FIRST", "MIDDLE", "LAST").contains(text(n, "labelType")),
                "处置缺少普通V2来源");
        for (String k :
                List.of(
                        "standardId",
                        "batchNo",
                        "orderId",
                        "matBarcode",
                        "deptCode",
                        "handleResult")) nullable(n, k);
        if (!n.get("standardId").isNull()) id(text(n, "standardId"));
        int total = integer(n, "num"), sample = integer(n, "inspectionNum");
        check(total > 0 && sample >= 0 && sample <= total, "送检量/取样量无效");
        integer(n, "usableNum");
        integer(n, "handleNum");
        for (String k : List.of("latestInspectionTime", "latestAppendTime"))
            check(
                    n.get(k).isNull()
                            || n.get(k).isIntegralNumber()
                                    && n.get(k).canConvertToLong()
                                    && n.get(k).longValue() >= 0,
                    "原检验时间类型无效");
    }

    public static String hash(String raw) {
        return QmsInspectionRevisionExecutionContract.fingerprint(raw);
    }

    public static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("处置原件编码失败", e);
        }
    }

    private static void fields(JsonNode n, Set<String> expected) {
        check(n != null && n.isObject(), "原件必须为对象");
        Set<String> names = new HashSet<>();
        n.fieldNames().forEachRemaining(names::add);
        check(names.equals(expected), "处置原件字段缺失或含未知字段");
    }

    private static int integer(JsonNode n, String k) {
        check(n.has(k) && n.get(k).isIntegralNumber() && n.get(k).canConvertToInt(), "缺少明确整数" + k);
        return n.get(k).intValue();
    }

    private static String text(JsonNode n, String k) {
        check(n.has(k) && n.get(k).isTextual(), "缺少文本" + k);
        return n.get(k).textValue();
    }

    private static String nullable(JsonNode n, String k) {
        check(n.has(k), "缺少可空字段" + k);
        return n.get(k).isNull() ? null : text(n, k);
    }

    private static Long id(String v) {
        check(v != null && v.matches("[1-9][0-9]{0,18}"), "主键格式无效");
        return Long.valueOf(v);
    }

    private static String str(Long n) {
        return n == null ? null : n.toString();
    }

    private static String strip(String s) {
        return s == null ? null : s.strip();
    }

    private static void check(boolean test, String message) {
        if (!test) throw new IllegalArgumentException(message);
    }
}
