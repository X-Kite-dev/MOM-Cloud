package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionOutcomeContract.*;
import static com.kitex.mom.qms.service.impl.QmsInspectionHistorySupport.require;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;

import java.util.*;

/** 正式run的只读原件核验；不以本地阶段或默认值冒充MES确认。 */
final class QmsInspectionRevisionRunCodec {
    private QmsInspectionRevisionRunCodec() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    static QmsInspectionRevisionExecutionEntity execution(
            QmsInspectionRevisionCoordinationMapper mapper, Long id, Long tenant, String factory) {
        var e = mapper.execution(id, tenant, factory);
        require(e != null, "缺少冻结执行意图");
        QmsInspectionRevisionCoordinationCodec.owner(
                e, mapper.request(id, tenant, factory), mapper.review(id, tenant, factory));
        return e;
    }

    static com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionOutcomeContract.Parsed outcome(
            QmsInspectionRevisionExecutionEntity e, QmsInspectionRevisionOutcomeEntity o) {
        require(
                o != null
                        && Objects.equals(o.getId(), e.getId())
                        && Objects.equals(o.getTenantId(), e.getTenantId())
                        && Objects.equals(o.getFactoryCode(), e.getFactoryCode())
                        && Objects.equals(o.getTaskId(), e.getTaskId())
                        && o.getPayload() != null
                        && hash(o.getPayload()).equals(o.getFingerprint())
                        && o.getCreatedAt() != null,
                "正式提交/中止原件损坏");
        var p = parse(o.getPayload(), e.getIntent());
        require(
                o.getDecision().equals(p.decision())
                        && o.getCreatedAt().toInstant().equals(p.occurredAt()),
                "正式结果索引与原文不一致");
        return p;
    }

    static MesInspectionRevisionExecutionReceiptDTO original(
            QmsInspectionRevisionExecutionEntity e, QmsInspectionRevisionRunReceiptEntity row) {
        require(
                row != null
                        && Objects.equals(row.getRequestId(), e.getId())
                        && Objects.equals(row.getTenantId(), e.getTenantId())
                        && Objects.equals(row.getFactoryCode(), e.getFactoryCode())
                        && Objects.equals(row.getTaskId(), e.getTaskId())
                        && row.getPayload() != null
                        && hash(row.getPayload()).equals(row.getFingerprint())
                        && row.getReceivedAt() != null,
                "正式MES回执原件损坏");
        try {
            var value =
                    JSON.readValue(
                            row.getPayload(), MesInspectionRevisionExecutionReceiptDTO.class);
            basic(e, value);
            return value;
        } catch (Exception ex) {
            throw new IllegalArgumentException("正式MES回执不可核验", ex);
        }
    }

    static void basic(
            QmsInspectionRevisionExecutionEntity e, MesInspectionRevisionExecutionReceiptDTO a) {
        var q =
                com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionExecutionContract.parse(
                                e.getIntent())
                        .request();
        require(
                a != null
                        && "MES_REVISION_EXECUTION_V1".equals(a.getContractVersion())
                        && Objects.equals(a.getRequestId(), e.getId())
                        && Objects.equals(a.getTenantId(), e.getTenantId())
                        && Objects.equals(a.getFactoryCode(), e.getFactoryCode())
                        && Objects.equals(a.getInspectionId(), e.getTaskId())
                        && Objects.equals(a.getIntentFingerprint(), e.getIntentFingerprint())
                        && Objects.equals(
                                a.getTaskCode(), q.path("source").path("orderNo").textValue())
                        && Objects.equals(
                                a.getProduceTaskCode(),
                                q.path("source").path("sourceTaskCode").textValue()),
                "正式MES回执身份不一致");
    }

    static void receipt(
            QmsInspectionRevisionExecutionEntity e,
            String op,
            QmsInspectionRevisionOutcomeEntity o,
            MesInspectionRevisionExecutionReceiptDTO seal,
            MesInspectionRevisionExecutionReceiptDTO a) {
        basic(e, a);
        if ("SEAL".equals(op)) {
            require(
                    "SEALED".equals(a.getStatus())
                            && a.isHeld()
                            && !a.isApplied()
                            && a.getGenRecordId() != null
                            && a.getGenRecordId() > 0
                            && fp(a.getSealFingerprint())
                            && date(a.getSealedAt())
                            && a.getOutcomeFingerprint() == null
                            && a.getResolvedAt() == null
                            && a.getEventId() == null
                            && a.getEventFingerprint() == null
                            && a.getCancellation() == null,
                    "MES未确认正式封存");
            return;
        }
        var proof = outcome(e, o);
        require(
                Objects.equals(a.getOutcomeFingerprint(), o.getFingerprint())
                        && date(a.getResolvedAt())
                        && !a.isHeld(),
                "MES正式终态/时间不一致");
        if ("APPLY".equals(op)) {
            require(
                    "COMMITTED".equals(proof.decision())
                            && seal != null
                            && Objects.equals(a.getSealFingerprint(), seal.getSealFingerprint())
                            && Objects.equals(a.getGenRecordId(), seal.getGenRecordId())
                            && Objects.equals(a.getSealedAt(), seal.getSealedAt())
                            && "APPLIED".equals(a.getStatus())
                            && a.isApplied()
                            && Objects.equals(
                                    a.getEventId().toString(),
                                    proof.event().path("eventId").textValue())
                            && Objects.equals(a.getEventFingerprint(), hash(proof.eventPayload()))
                            && a.getCancellation() == null,
                    "MES未确认同一修订已应用");
        } else {
            require(
                    "ABORT".equals(op)
                            && "ABORTED".equals(proof.decision())
                            && "ABORTED".equals(a.getStatus())
                            && !a.isApplied()
                            && a.getEventId() == null
                            && a.getEventFingerprint() == null
                            && a.getCancellation() != null,
                    "MES未确认永久中止");
            if (seal != null)
                require(
                        Objects.equals(a.getSealFingerprint(), seal.getSealFingerprint())
                                && Objects.equals(a.getGenRecordId(), seal.getGenRecordId())
                                && Objects.equals(a.getSealedAt(), seal.getSealedAt()),
                        "中止回执丢失封存依据");
        }
    }

    static MesInspectionRevisionExecutionReceiptDTO seal(
            QmsInspectionRevisionRunMapper mapper, QmsInspectionRevisionExecutionEntity e) {
        MesInspectionRevisionExecutionReceiptDTO result = null;
        var rows = mapper.receipts(e.getId(), e.getTenantId(), e.getFactoryCode());
        require(rows.size() <= 3, "正式回执超限");
        for (var row : rows)
            if ("SEAL".equals(row.getOperation())) {
                require(result == null, "封存回执重复");
                result = original(e, row);
                receipt(e, "SEAL", null, null, result);
            }
        return result;
    }

    private static boolean fp(String v) {
        return v != null && v.matches("[0-9a-f]{64}");
    }

    private static boolean date(Date v) {
        return v != null && v.getTime() > 0 && v.getTime() <= System.currentTimeMillis() + 15000;
    }
}
