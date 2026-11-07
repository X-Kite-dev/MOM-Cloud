package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesWorkpieceTimeDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesWorkpieceTimeSessionEntity;
import com.kitex.mom.mes.mapper.MesWorkpieceTimeMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 只读取当前会话页的数据库START来源；不升级原人工观察依据。 */
final class MesWorkpieceEntrySources {
    private MesWorkpieceEntrySources() {}

    static boolean available(MesWorkpieceTimeMapper rows) {
        int installed = rows.entryTableCount();
        if (installed == 0) return false;
        require(installed == 2 && Boolean.TRUE.equals(rows.entryReady()), "单件入站来源安装不完整，禁止展示已留存来源");
        return true;
    }

    static MesWorkpieceTimeDTO.EntrySources read(
            MesWorkpieceTimeMapper rows,
            ObjectMapper json,
            MesTaskArrangeEntity arrange,
            List<MesWorkpieceTimeSessionEntity> sessions) {
        if (!available(rows))
            return new MesWorkpieceTimeDTO.EntrySources("SCHEMA_UNAVAILABLE", List.of());
        if (sessions.isEmpty()) return new MesWorkpieceTimeDTO.EntrySources("READY", List.of());
        var indexed = new HashMap<String, MesWorkpieceTimeSessionEntity>();
        sessions.forEach(s -> indexed.put(s.getId().toString(), s));
        var seen = new HashSet<String>();
        var result = new ArrayList<MesWorkpieceTimeDTO.EntrySource>();
        for (var row :
                rows.entrySources(
                        arrange.getTenantId(),
                        arrange.getFactoryCode(),
                        arrange.getId(),
                        sessions.stream().map(MesWorkpieceTimeSessionEntity::getId).toList())) {
            var session = indexed.get(row.sessionId());
            require(
                    session != null
                            && seen.add(row.sessionId())
                            && arrange.getTenantId().toString().equals(row.tenantId())
                            && arrange.getFactoryCode().equals(row.factoryCode())
                            && arrange.getId().toString().equals(row.arrangeId()),
                    "单件入站来源与当前派工会话作用域不一致");
            result.add(verify(json, row, session).summary());
        }
        return new MesWorkpieceTimeDTO.EntrySources("READY", List.copyOf(result));
    }

    record Verified(MesWorkpieceTimeDTO.EntrySource summary, JsonNode raw) {}

    static Verified verify(
            ObjectMapper json,
            MesWorkpieceTimeMapper.EntryRow row,
            MesWorkpieceTimeSessionEntity session) {
        require(
                session != null
                        && Objects.equals(row.sessionId(), String.valueOf(session.getId()))
                        && Objects.equals(row.tenantId(), String.valueOf(session.getTenantId()))
                        && Objects.equals(row.factoryCode(), session.getFactoryCode())
                        && Objects.equals(row.arrangeId(), String.valueOf(session.getArrangeId()))
                        && "SN".equals(session.getIdentifierType())
                        && "0".equals(session.getDelFlag()),
                "单件来源原会话归属损坏");
        require(Objects.equals(sha(row.raw()), row.fingerprint()), "单件入站来源原文指纹损坏");
        JsonNode raw = parse(json, row.raw()), receipt = parse(json, row.receiptJson());
        MesWorkpieceTimeDTO.Receipt original;
        try {
            original = json.readValue(row.receiptJson(), MesWorkpieceTimeDTO.Receipt.class);
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw new CheckedException("单件入站原START回执损坏");
        }
        require(
                eq(raw, "schemaVersion", "MES_WORKPIECE_ENTRY@1")
                        && eq(raw, "captureBasis", "OPERATOR_OBSERVED")
                        && eq(raw, "tenantId", row.tenantId())
                        && eq(raw, "factoryCode", row.factoryCode())
                        && eq(raw, "arrangeId", row.arrangeId())
                        && eq(raw, "sessionId", row.sessionId())
                        && eq(raw, "operationId", row.operationId())
                        && eq(raw, "identifierType", "SN")
                        && eq(raw, "identifier", session.getIdentifier())
                        && eq(raw, "workerCode", session.getWorkerCode())
                        && eq(raw, "taskCode", session.getTaskCode())
                        && eq(raw, "produceTaskCode", session.getProduceTaskCode())
                        && eq(raw, "receiptJson", row.receiptJson())
                        && eq(raw, "requestHash", row.requestHash())
                        && eq(receipt, "contractVersion", "MES_WORKPIECE_TIME_V1")
                        && eq(receipt, "action", "START")
                        && eq(receipt, "sessionId", row.sessionId())
                        && eq(receipt, "arrangeId", row.arrangeId())
                        && eq(receipt, "requestId", raw.path("requestId").asText())
                        && eq(receipt, "identifierType", "SN")
                        && eq(receipt, "identifier", session.getIdentifier())
                        && eq(receipt, "captureBasis", "OPERATOR_OBSERVED")
                        && raw.path("capturedAt").equals(receipt.path("capturedAt"))
                        && original.capturedAt() != null
                        && original.versionNo() == 1
                        && "RUNNING".equals(original.status()),
                "单件入站来源与原START回执或会话身份不一致");
        return new Verified(
                new MesWorkpieceTimeDTO.EntrySource(
                        row.sessionId(),
                        session.getTaskCode(),
                        session.getProduceTaskCode(),
                        text(raw, "sourceRouteNodeCode"),
                        session.getWorkerCode(),
                        text(raw, "worksiteCode"),
                        raw.path("requestId").asText(),
                        original.capturedAt().toString(),
                        row.fingerprint()),
                raw);
    }

    private static boolean eq(JsonNode value, String key, String expected) {
        return value.path(key).isTextual() && Objects.equals(value.path(key).textValue(), expected);
    }

    private static String text(JsonNode value, String key) {
        return value.path(key).isTextual() ? value.path(key).textValue() : null;
    }

    private static JsonNode parse(ObjectMapper json, String raw) {
        try {
            return json.readTree(raw);
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw new CheckedException("单件入站来源原文损坏");
        }
    }

    private static String sha(String raw) {
        require(raw != null, "单件入站来源原文缺失");
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
