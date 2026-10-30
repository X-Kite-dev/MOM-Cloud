package com.kitex.mom.qms.service.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureSourceContract;
import com.kitex.mom.qms.entity.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 只核发件原件及来源身份；不认证检验修订许可或按件合格率。 */
final class QmsInspectionEventDeliveryCodec {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> ACTIONS =
            Set.of("CREATE", "APPEND", "RESULT", "CANCELED", "EXEMPTION", "DISPOSITION");

    private QmsInspectionEventDeliveryCodec() {}

    static boolean eligible(QmsInspectionEventEntity e) {
        if (!"PROCEDURE".equals(e.getInspectionType())) return false;
        JsonNode source = raw(e).path("source");
        var request = source.get("sourceRequestId");
        if (request == null || request.isNull()) return false;
        require(request.isTextual(), "检验原件来源请求类型不正确");
        if (!request.textValue().startsWith(InspectionProcedureSourceContract.REQUEST_PREFIX))
            return false;
        require(
                source.path("sourceTaskCode").isTextual()
                        && !source.get("sourceTaskCode").textValue().isBlank()
                        && source.path("sourceRequestFingerprint").isTextual()
                        && source.get("sourceRequestFingerprint")
                                .textValue()
                                .matches("[0-9a-f]{64}"),
                "普通MES检验原件缺少V2来源身份");
        return true;
    }

    static JsonNode raw(QmsInspectionEventEntity e) {
        require(
                e != null
                        && e.getId() != null
                        && e.getId() > 0
                        && e.getTenantId() != null
                        && e.getTenantId() > 0
                        && e.getFactoryCode() != null
                        && !e.getFactoryCode().isBlank()
                        && e.getTaskId() != null
                        && e.getTaskId() > 0
                        && e.getHistoryId() != null
                        && e.getHistoryId() > 0
                        && e.getSequenceNo() != null
                        && e.getSequenceNo() > 0
                        && e.getSequenceNo() <= 5000
                        && e.getPayload() != null
                        && e.getPayload().length() <= 65536
                        && e.getPayload().getBytes(StandardCharsets.UTF_8).length <= 65536
                        && e.getFingerprint() != null
                        && e.getFingerprint().matches("[0-9a-f]{64}")
                        && hash(e.getPayload()).equals(e.getFingerprint())
                        && e.getOccurredAt() != null
                        && ACTIONS.contains(Objects.toString(e.getEventType(), "")),
                "普通检验原件身份、长度或摘要无效");
        try {
            var j = JSON.readTree(e.getPayload());
            require(
                    j != null
                            && j.isObject()
                            && j.path("schemaVersion").isInt()
                            && j.get("schemaVersion").intValue()
                                    == ("DISPOSITION".equals(e.getEventType()) ? 2 : 1)
                            && "QMS".equals(j.path("sourceSystem").textValue())
                            && same(j, "eventId", e.getId())
                            && same(j, "tenantId", e.getTenantId())
                            && same(j, "taskId", e.getTaskId())
                            && same(j, "historyId", e.getHistoryId())
                            && Objects.equals(j.path("factoryCode").textValue(), e.getFactoryCode())
                            && Objects.equals(
                                    j.path("inspectionType").textValue(), e.getInspectionType())
                            && Objects.equals(j.path("eventType").textValue(), e.getEventType())
                            && j.path("sequenceNo").isIntegralNumber()
                            && j.get("sequenceNo").canConvertToLong()
                            && j.get("sequenceNo").longValue() == e.getSequenceNo()
                            && same(j, "previousEventId", e.getPreviousEventId())
                            && j.has("previousFingerprint")
                            && Objects.equals(
                                    j.path("previousFingerprint").isNull()
                                            ? null
                                            : j.path("previousFingerprint").textValue(),
                                    e.getPreviousFingerprint())
                            && Objects.equals(
                                    j.path("occurredAt").textValue(),
                                    e.getOccurredAt().toInstant().toString())
                            && j.path("source").isObject()
                            && j.path("firstPassEligible").isBoolean()
                            && !j.get("firstPassEligible").booleanValue()
                            && j.path("dataGaps").isArray(),
                    "普通检验原件头部与永久索引不一致");
            if ("DISPOSITION".equals(e.getEventType()))
                com.kitex.mom.qms.dto.inspection.QmsProcedureDispositionContract.validateEvent(j);
            return j;
        } catch (CheckedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CheckedException("普通检验原件不能解析");
        }
    }

    static void verify(QmsInspectionEventOutboxEntity row, QmsInspectionEventEntity e) {
        require(
                e != null
                        && Objects.equals(row.getId(), e.getId())
                        && Objects.equals(row.getTenantId(), e.getTenantId())
                        && Objects.equals(row.getFactoryCode(), e.getFactoryCode())
                        && Objects.equals(row.getTaskId(), e.getTaskId())
                        && Objects.equals(row.getSequenceNo(), e.getSequenceNo())
                        && Objects.equals(row.getFingerprint(), e.getFingerprint())
                        && eligible(e),
                "普通检验发件引用与真实原件不一致");
    }

    private static boolean same(JsonNode j, String field, Long value) {
        var n = j.get(field);
        return n != null
                && (value == null
                        ? n.isNull()
                        : n.isTextual() && value.toString().equals(n.textValue()));
    }

    static String hash(String s) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }
}
