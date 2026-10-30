package com.kitex.mom.qms.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.mes.dto.MesReworkInspectionResultDTO;
import com.kitex.mom.qms.entity.QmsReworkOrderEntity;
import com.kitex.mom.qms.entity.QmsReworkResultOutboxEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** 仅本协议的版本化原文，不改变全局JSON配置或MES接收合同。 */
final class QmsReworkResultCodec {
    private static final ObjectMapper JSON = new ObjectMapper();

    private QmsReworkResultCodec() {}

    static String encode(QmsReworkResultOutboxEntity row, MesReworkInspectionResultDTO result) {
        try {
            return JSON.writeValueAsString(
                    new Envelope(
                            1,
                            row.getId(),
                            row.getTenantId(),
                            row.getFactoryCode(),
                            row.getOrderId(),
                            row.getSequenceNo(),
                            result));
        } catch (Exception e) {
            throw new IllegalStateException("复检发送原文生成失败");
        }
    }

    static MesReworkInspectionResultDTO decode(
            QmsReworkResultOutboxEntity row, QmsReworkOrderEntity order) {
        try {
            require(
                    row.getPayload() != null
                            && row.getPayload().getBytes(StandardCharsets.UTF_8).length <= 4096
                            && hash(row.getPayload()).equals(row.getFingerprint()),
                    "复检发送原文指纹不一致");
            Envelope e = JSON.readValue(row.getPayload(), Envelope.class);
            require(
                    e.schemaVersion() == 1
                            && Objects.equals(e.eventId(), row.getId())
                            && Objects.equals(e.tenantId(), row.getTenantId())
                            && Objects.equals(e.factoryCode(), row.getFactoryCode())
                            && Objects.equals(e.orderId(), row.getOrderId())
                            && Objects.equals(e.sequenceNo(), row.getSequenceNo())
                            && Objects.equals(order.getId(), row.getOrderId())
                            && Objects.equals(order.getTenantId(), row.getTenantId())
                            && Objects.equals(order.getFactoryCode(), row.getFactoryCode()),
                    "复检发送身份不一致");
            var r = e.result();
            require(
                    r != null
                            && Objects.equals(r.getRequestId(), order.getRequestId())
                            && Objects.equals(r.getQmsTaskId(), order.getReinspectionTaskId())
                            && Objects.equals(r.getInspectionType(), order.getInspectionType()),
                    "复检发送与协调单登记不一致");
            require(
                    Set.of("PROCEDURE", "PRODUCT")
                                    .contains(Objects.toString(r.getInspectionType(), ""))
                            && Set.of(
                                            "QUALIFIED",
                                            "UN_QUALIFIED",
                                            "RELEASE",
                                            "RECEIVE",
                                            "EXEMPTION")
                                    .contains(Objects.toString(r.getResult(), ""))
                            && r.getTotalNum() != null
                            && r.getTotalNum() > 0
                            && r.getUsableNum() != null
                            && r.getHandleNum() != null
                            && r.getUsableNum() >= 0
                            && r.getUsableNum() <= r.getHandleNum()
                            && r.getHandleNum() <= r.getTotalNum()
                            && (!"QUALIFIED".equals(r.getResult())
                                    || (r.getUsableNum().equals(r.getTotalNum())
                                            && r.getHandleNum().equals(r.getTotalNum()))),
                    "复检发送数量或结论无效");
            return r;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("复检发送原文不可解析");
        }
    }

    static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record Envelope(
            int schemaVersion,
            Long eventId,
            Long tenantId,
            String factoryCode,
            Long orderId,
            Long sequenceNo,
            MesReworkInspectionResultDTO result) {}
}
