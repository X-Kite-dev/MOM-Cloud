package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionEvidenceChecks.require;

import com.fasterxml.jackson.databind.*;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.qms.dto.QmsReworkCompletionDTO;
import com.kitex.mom.qms.entity.QmsReworkOrderEntity;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/** V1 received business command, not the HTTP wire bytes or the sender's outbox. */
final class QmsReworkCompletionReceiptCodec {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private QmsReworkCompletionReceiptCodec() {}

    public record Command(
            String requestId,
            String mesTaskCode,
            String mesProduceTaskCode,
            int completedQuantity,
            String processCode,
            String processName) {}

    public record Snapshot(
            int schemaVersion,
            Long tenantId,
            String factoryCode,
            String inspectionType,
            Long orderId,
            Long originalTaskId,
            Long handleRecordId,
            Long childTaskId,
            String requestId,
            String originalMesTaskCode,
            String originalMesProduceTaskCode,
            String matCode,
            String batchNo,
            String reworkProcessCode,
            int quantity,
            Long receivedBy,
            long receivedAt,
            Command command) {}

    static Command command(QmsReworkCompletionDTO c) {
        require(
                c != null
                        && code(c.getRequestId())
                        && code(c.getMesTaskCode())
                        && code(c.getMesProduceTaskCode())
                        && code(c.getProcessCode())
                        && c.getCompletedQuantity() != null
                        && (c.getProcessName() == null || c.getProcessName().length() <= 1000),
                "完工回执缺少有效来源，或工序名称超过1000字符");
        int quantity;
        try {
            quantity = c.getCompletedQuantity().intValueExact();
        } catch (ArithmeticException invalid) {
            throw new CheckedException("完工回执数量须为可支持的正整数", invalid);
        }
        require(quantity > 0, "完工回执数量须为正整数");
        return new Command(
                c.getRequestId(),
                c.getMesTaskCode(),
                c.getMesProduceTaskCode(),
                quantity,
                c.getProcessCode(),
                c.getProcessName());
    }

    static void validate(Command c) {
        require(
                c != null
                        && code(c.requestId())
                        && code(c.mesTaskCode())
                        && code(c.mesProduceTaskCode())
                        && code(c.processCode())
                        && c.completedQuantity() > 0
                        && (c.processName() == null || c.processName().length() <= 1000),
                "原始完工回执命令无效");
    }

    static Snapshot snapshot(QmsReworkOrderEntity row, Command command, Long actor, long time) {
        require(row.getQuantity() != null, "完工协调量缺失");
        int quantity;
        try {
            quantity = row.getQuantity().intValueExact();
        } catch (ArithmeticException invalid) {
            throw new CheckedException("完工协调量须为可支持的正整数", invalid);
        }
        return new Snapshot(
                1,
                row.getTenantId(),
                row.getFactoryCode(),
                row.getInspectionType(),
                row.getId(),
                row.getInspectionTaskId(),
                row.getHandleRecordId(),
                row.getReinspectionTaskId(),
                row.getRequestId(),
                row.getOriginalMesTaskCode(),
                row.getOriginalMesProduceTaskCode(),
                row.getMatCode(),
                row.getBatchNo(),
                row.getReworkProcessCode(),
                quantity,
                actor,
                time,
                command);
    }

    static String write(Snapshot value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new CheckedException("完工回执原文不能序列化", invalid);
        }
    }

    static Snapshot read(String value) {
        try {
            return JSON.readValue(value, Snapshot.class);
        } catch (Exception invalid) {
            throw new CheckedException("完工回执原文结构损坏", invalid);
        }
    }

    static String sha(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static boolean code(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= 200
                && value.equals(value.trim())
                && value.chars().noneMatch(Character::isISOControl);
    }
}
