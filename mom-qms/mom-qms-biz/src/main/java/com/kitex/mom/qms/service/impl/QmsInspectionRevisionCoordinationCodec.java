package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionHistorySupport.require;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesInspectionRevisionAdmissionDTO;
import com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionExecutionContract;
import com.kitex.mom.qms.entity.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 原件核验和MES回执身份核验；不依据发送成功推断执行或未冻结。 */
final class QmsInspectionRevisionCoordinationCodec {
    private QmsInspectionRevisionCoordinationCodec() {}

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    static String hash(String raw) {
        return QmsInspectionRevisionExecutionContract.fingerprint(raw);
    }

    static QmsInspectionRevisionExecutionContract.Parsed verify(
            QmsInspectionRevisionExecutionEntity e) {
        QmsInspectionEventWorker.scope();
        require(
                e != null
                        && e.getId() != null
                        && e.getTaskId() != null
                        && e.getIntent() != null
                        && Objects.equals(hash(e.getIntent()), e.getIntentFingerprint()),
                "执行意图原件缺失或损坏");
        var p = QmsInspectionRevisionExecutionContract.parse(e.getIntent());
        var q = p.request();
        require(
                Objects.equals(
                                e.getTenantId(),
                                com.kitex.common.data.tenant.TenantContextHolder.getTenantId())
                        && Objects.equals(
                                e.getFactoryCode(),
                                com.kitex.common.data.orgFilter.FactoryContextHolder
                                        .getFactoryCode())
                        && e.getId().toString().equals(q.path("requestId").textValue())
                        && e.getTaskId().toString().equals(q.path("taskId").textValue())
                        && e.getTenantId().toString().equals(q.path("tenantId").textValue())
                        && e.getFactoryCode().equals(q.path("factoryCode").textValue())
                        && Objects.equals(e.getExecutorId(), p.executorId())
                        && e.getCreatedAt() != null
                        && e.getCreatedAt().toInstant().equals(p.requestedAt()),
                "执行意图与实际组织或索引不一致");
        return p;
    }

    static void owner(
            QmsInspectionRevisionExecutionEntity e,
            QmsInspectionRevisionEntity request,
            QmsInspectionRevisionReviewEntity review) {
        verify(e);
        require(
                request != null
                        && review != null
                        && e.getIntent()
                                .equals(
                                        QmsInspectionRevisionExecutionContract.freeze(
                                                request,
                                                review,
                                                e.getExecutorId(),
                                                e.getCreatedAt())),
                "冻结执行意图与原申请/独立审核不一致");
    }

    static void continuity(
            MesInspectionRevisionAdmissionDTO previous,
            MesInspectionRevisionAdmissionDTO canceled) {
        if (previous == null) return;
        require(
                Objects.equals(previous.getGenRecordId(), canceled.getGenRecordId())
                        && Objects.equals(previous.getReservedAt(), canceled.getReservedAt())
                        && Objects.equals(
                                previous.getAdmissionFingerprint(),
                                canceled.getAdmissionFingerprint()),
                "取消回执丢失或替换已确认准入依据");
    }

    static void cancel(
            QmsInspectionRevisionExecutionEntity e, QmsInspectionRevisionCancelEntity c) {
        if (c == null) return;
        require(
                Objects.equals(c.getId(), e.getId())
                        && Objects.equals(c.getTenantId(), e.getTenantId())
                        && Objects.equals(c.getFactoryCode(), e.getFactoryCode())
                        && Objects.equals(c.getTaskId(), e.getTaskId())
                        && c.getCanceledBy() != null
                        && c.getCanceledBy() > 0
                        && c.getCanceledAt() != null
                        && !c.getCanceledAt().before(e.getCreatedAt())
                        && c.getReason() != null
                        && !c.getReason().isBlank()
                        && c.getReason().length() <= 1000
                        && c.getReason().equals(c.getReason().strip()),
                "取消意图原件与执行绑定不一致");
    }

    static void receipt(
            QmsInspectionRevisionExecutionEntity e,
            QmsInspectionRevisionCancelEntity c,
            String operation,
            MesInspectionRevisionAdmissionDTO a) {
        var p = verify(e);
        cancel(e, c);
        var q = p.request();
        require(
                a != null
                        && "MES_REVISION_ADMISSION_V1".equals(a.getContractVersion())
                        && Objects.equals(a.getRequestId(), e.getId())
                        && Objects.equals(a.getTenantId(), e.getTenantId())
                        && Objects.equals(a.getFactoryCode(), e.getFactoryCode())
                        && Objects.equals(a.getInspectionId(), e.getTaskId())
                        && Objects.equals(
                                a.getTaskCode(), q.path("source").path("orderNo").textValue())
                        && Objects.equals(
                                a.getProduceTaskCode(),
                                q.path("source").path("sourceTaskCode").textValue())
                        && Objects.equals(
                                a.getBaseEventId().toString(), q.path("baseEventId").textValue())
                        && Objects.equals(
                                a.getBaseFingerprint(), q.path("baseFingerprint").textValue())
                        && Objects.equals(a.getIntentFingerprint(), e.getIntentFingerprint())
                        && !a.isApplied(),
                "MES协调回执身份或执行声明不一致");
        if ("RESERVE".equals(operation)) {
            require(
                    "RESERVED".equals(a.getStatus())
                            && a.isHeld()
                            && a.getCanceledAt() == null
                            && a.getCanceledBy() == null
                            && a.getCancelReason() == null,
                    "MES未确认为同意图冻结");
        } else {
            require(
                    "CANCEL".equals(operation)
                            && c != null
                            && "CANCELED".equals(a.getStatus())
                            && !a.isHeld()
                            && Objects.equals(a.getCanceledBy(), c.getCanceledBy())
                            && Objects.equals(a.getCancelReason(), c.getReason())
                            && date(a.getCanceledAt()),
                    "MES未确认同人同原因的永久取消");
        }
        if (a.getReservedAt() != null)
            require(
                    date(a.getReservedAt())
                            && a.getGenRecordId() != null
                            && a.getGenRecordId() > 0
                            && a.getAdmissionFingerprint() != null
                            && a.getAdmissionFingerprint().matches("[0-9a-f]{64}")
                            && (a.getCanceledAt() == null
                                    || !a.getCanceledAt().before(a.getReservedAt())),
                    "MES准入依据或时间不完整");
        else
            require(
                    "CANCEL".equals(operation)
                            && a.getGenRecordId() == null
                            && a.getAdmissionFingerprint() == null,
                    "缺少实际准入原件，禁止推算冻结");
    }

    static MesInspectionRevisionAdmissionDTO original(
            QmsInspectionRevisionExecutionEntity e,
            QmsInspectionRevisionCancelEntity c,
            QmsInspectionRevisionReceiptEntity r) {
        require(
                r != null
                        && r.getId() != null
                        && r.getId() > 0
                        && Objects.equals(r.getRequestId(), e.getId())
                        && Objects.equals(r.getTenantId(), e.getTenantId())
                        && Objects.equals(r.getFactoryCode(), e.getFactoryCode())
                        && Objects.equals(r.getTaskId(), e.getTaskId())
                        && r.getRecordedAt() != null
                        && r.getPayload() != null
                        && r.getPayload().getBytes(StandardCharsets.UTF_8).length <= 65536
                        && Objects.equals(hash(r.getPayload()), r.getFingerprint()),
                "协调回执原件或索引损坏");
        try {
            var a = JSON.readValue(r.getPayload(), MesInspectionRevisionAdmissionDTO.class);
            receipt(e, c, r.getOperation(), a);
            return a;
        } catch (CheckedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CheckedException("协调回执原文不可解析", ex);
        }
    }

    static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            throw new CheckedException("协调原件编码失败", ex);
        }
    }

    private static boolean date(Date d) {
        return d != null && d.getTime() > 0 && d.getTime() <= System.currentTimeMillis() + 15000;
    }
}
