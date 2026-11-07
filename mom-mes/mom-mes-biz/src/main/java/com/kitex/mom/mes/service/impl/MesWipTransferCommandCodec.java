package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesWipTransferCommand;

import java.util.*;

/** 原V1命令规则和Hutool指纹；不可改成另一个序列化协议。 */
final class MesWipTransferCommandCodec {
    private MesWipTransferCommandCodec() {}

    static String raw(MesWipTransferCommand c) {
        return JSONUtil.toJsonStr(c);
    }

    static String fingerprint(MesWipTransferCommand c, Long actor) {
        return sha(JSONUtil.toJsonStr(List.of(c, String.valueOf(actor))));
    }

    static String sha(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }

    static void normalize(MesWipTransferCommand c) {
        require(
                c != null
                        && c.getRequestId() != null
                        && c.getRequestId().matches("[A-Za-z0-9_-]{16,64}"),
                "请求号须为16至64位安全字符");
        require(
                Set.of(
                                "DISPATCH",
                                "RECEIVE",
                                "REQUEST_RETURN",
                                "RECEIVE_RETURN",
                                "RECEIVE_QUARANTINE",
                                "HOLD",
                                "RESUME")
                        .contains(Objects.toString(c.getOperationType(), "")),
                "不支持的转运命令");
        require(
                StrUtil.isNotBlank(c.getReason()) && c.getReason().length() <= 500,
                "须填写500字以内的实际原因/交接说明");
        c.setReason(c.getReason().trim());
        if ("RECEIVE_QUARANTINE".equals(c.getOperationType())) {
            require(
                    Set.of("DAMAGED", "EXPIRED", "QUALITY_SUSPECT")
                            .contains(Objects.toString(c.getExceptionType(), "")),
                    "请选择破损、过期或质量疑似；丢失物料不能作为实收");
            require(
                    StrUtil.isNotBlank(c.getSegregationEvidence())
                            && c.getSegregationEvidence().length() <= 1000,
                    "须填写1000字以内现场隔离位置、标签及佐证编号");
            c.setSegregationEvidence(c.getSegregationEvidence().trim());
            require(Boolean.TRUE.equals(c.getSegregationConfirmed()), "须确认物料已实际收到并完成现场隔离标识");
        } else
            require(
                    c.getExceptionType() == null
                            && c.getSegregationEvidence() == null
                            && c.getSegregationConfirmed() == null,
                    "非隔离接收不能附带质量案件字段");
        if (Set.of("DISPATCH", "RECEIVE", "RECEIVE_RETURN", "RECEIVE_QUARANTINE")
                .contains(c.getOperationType())) {
            require(
                    c.getQuantity() != null
                            && c.getQuantity().signum() > 0
                            && c.getQuantity().precision() <= 24
                            && Math.max(c.getQuantity().scale(), 0) <= 8,
                    "数量须为正数，最多24位且8位小数");
            c.setQuantity(c.getQuantity().stripTrailingZeros());
        } else require(c.getQuantity() == null, "状态命令不能修改数量");
        for (String value :
                Arrays.asList(
                        c.getFromLocationCode(),
                        c.getToLocationCode(),
                        c.getScannedLocationCode(),
                        c.getObservedBarcode()))
            require(value == null || value.length() <= 200, "库位或条码过长");
        target(c);
        if ("DISPATCH".equals(c.getOperationType())) {
            require(c.getDueTime() != null, "发出须填写承诺到达时间");
            require(
                    c.getExpectedSourceQuantity() != null
                            && c.getExpectedSourceQuantity().signum() > 0,
                    "发出须先读取并核对当前库存数量");
            c.setExpectedSourceQuantity(c.getExpectedSourceQuantity().stripTrailingZeros());
        } else
            require(
                    c.getExpectedRevision() != null && c.getExpectedRevision() > 0,
                    "交接须提供观察到的单据版本");
    }

    static void target(MesWipTransferCommand command) {
        var c = command.getDispatchTarget();
        if (c == null) return;
        require("DISPATCH".equals(command.getOperationType()), "仅发出可附带工序目标");
        require(
                com.kitex.mom.mes.dto.MesWipDispatchTargetDTO.SCHEMA.equals(c.getSchemaVersion()),
                "工序目标协议不正确");
        for (String value :
                Arrays.asList(
                        c.getTenantId(),
                        c.getActorId(),
                        c.getSourceStockId(),
                        c.getSourceReportId(),
                        c.getArrangeId(),
                        c.getLocationId(),
                        c.getBindingRevision())) MesReceivingTargetRules.id(value);
        require(
                command.getSourceStockId() != null
                        && command.getSourceStockId().toString().equals(c.getSourceStockId()),
                "目标选择属于其他原库存");
        require(
                MesReceivingTargetRules.code(c.getFactoryCode())
                        && MesReceivingTargetRules.code(c.getWorksiteCode())
                        && c.getNodeCode() != null
                        && c.getNodeCode().matches("RN-[1-9][0-9]{0,18}")
                        && c.getReferenceHash() != null
                        && c.getReferenceHash().matches("[0-9a-f]{64}"),
                "工序目标字段不完整");
    }

    static cn.hutool.json.JSONObject targetEvidence(
            com.kitex.mom.mes.dto.MesWipDispatchTargetDTO.Validated target) {
        try {
            return JSONUtil.parseObj(
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(target));
        } catch (Exception e) {
            throw new CheckedException("工序目标原依据不能留存", e);
        }
    }
}
