package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.qms.dto.QmsReworkCompletionDTO;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

final class MesReworkCompletionCodec {
    private MesReworkCompletionCodec() {}

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    record Command(
            String requestId,
            String mesTaskCode,
            String mesProduceTaskCode,
            int completedQuantity,
            String processCode,
            String processName) {}

    record Snapshot(
            int schemaVersion,
            Long outboxId,
            Long tenantId,
            String factoryCode,
            Long taskId,
            String taskCode,
            Long originalTaskId,
            String originalTaskCode,
            String sourceProduceTaskCode,
            String inspectionType,
            Long inspectionTaskId,
            Long handleRecordId,
            String matCode,
            String reworkProcessCode,
            String reworkRouteNodeCode,
            Long produceTaskId,
            String produceNodeCode,
            Long createdBy,
            long createdAt,
            Command command) {}

    static Command command(MesTaskEntity task, MesProduceTaskEntity op, BigDecimal quantity) {
        var c =
                new Command(
                        task.getReworkRequestId(),
                        task.getCode(),
                        op.getCode(),
                        integer(quantity),
                        op.getRouteCode(),
                        op.getRouteName());
        validate(c);
        return c;
    }

    static int integer(BigDecimal value) {
        try {
            require(value != null, "返工完工数量缺失");
            int n = value.intValueExact();
            require(n > 0, "返工完工数量须为正整数");
            return n;
        } catch (ArithmeticException invalid) {
            throw new CheckedException("返工完工数量须为可支持的正整数", invalid);
        }
    }

    static void validate(Command c) {
        require(
                c != null
                        && code(c.requestId())
                        && code(c.mesTaskCode())
                        && code(c.mesProduceTaskCode())
                        && code(c.processCode())
                        && c.completedQuantity() > 0
                        && (c.processName() == null
                                || c.processName().length() <= 1000
                                        && c.processName().indexOf(0) < 0),
                "返工完工发送命令无效或名称超过1000字符");
    }

    static Snapshot snapshot(
            Long id,
            MesTaskEntity task,
            MesTaskEntity source,
            MesProduceTaskEntity op,
            Command c,
            Long actor,
            long time) {
        return new Snapshot(
                1,
                id,
                task.getTenantId(),
                task.getFactoryCode(),
                task.getId(),
                task.getCode(),
                source.getId(),
                source.getCode(),
                task.getSourceProduceTaskCode(),
                task.getSourceInspectionType(),
                task.getSourceInspectionTaskId(),
                task.getSourceHandleRecordId(),
                task.getMatCode(),
                task.getReworkProcessCode(),
                task.getReworkRouteNodeCode(),
                op.getId(),
                op.getSourceRouteNodeCode(),
                actor,
                time,
                c);
    }

    static String encode(Snapshot s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (Exception e) {
            throw new CheckedException("返工发送原件不能序列化", e);
        }
    }

    static Snapshot decode(String raw) {
        try {
            return JSON.readValue(raw, Snapshot.class);
        } catch (Exception e) {
            throw new CheckedException("返工发送原件结构损坏", e);
        }
    }

    static String hash(String s) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static QmsReworkCompletionDTO dto(Command c) {
        validate(c);
        var d = new QmsReworkCompletionDTO();
        d.setRequestId(c.requestId());
        d.setMesTaskCode(c.mesTaskCode());
        d.setMesProduceTaskCode(c.mesProduceTaskCode());
        d.setCompletedQuantity(BigDecimal.valueOf(c.completedQuantity()));
        d.setProcessCode(c.processCode());
        d.setProcessName(c.processName());
        return d;
    }

    static boolean code(String s) {
        return s != null
                && !s.isBlank()
                && s.length() <= 200
                && s.equals(s.trim())
                && s.chars().noneMatch(Character::isISOControl);
    }

    static boolean primary(Long id) {
        return id != null && id > 0;
    }

    static void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }
}
