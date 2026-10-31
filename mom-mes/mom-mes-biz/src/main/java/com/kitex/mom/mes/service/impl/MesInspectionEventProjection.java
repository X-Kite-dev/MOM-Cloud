package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesQualityGateItemDTO;
import com.kitex.mom.mes.entity.MesInspectionEventApplicationEntity;
import com.kitex.mom.mes.entity.MesInspectionEventInboxEntity;
import com.kitex.mom.mes.entity.MesInspectionEventStateEntity;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import com.kitex.mom.mes.mapper.MesInspectionEventStateMapper;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/**
 * 只接受本地永久原件和已提交应用回执；冲突/待应用不能借旧缓存或远端值放行。
 */
@Service
@RequiredArgsConstructor
public class MesInspectionEventProjection {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> TERMINAL =
            Set.of("QUALIFIED", "UN_QUALIFIED", "EXEMPTION", "CANCELED", "RELEASE", "RECEIVE");
    private final MesInspectionEventStateMapper states;
    private final MesInspectionEventInboxService inbox;
    private final MesQualityGateSourceEvidence sources;
    private final MesInspectionRevisionHold revisionHolds;

    private static int nz(Integer n) {
        return n == null ? 0 : n;
    }

    private static Resolution missing(
            MesInspectionEventStateEntity s, String status, String message, Long blocked) {
        return new Resolution(
                null,
                s == null ? null : s.getLastEventId(),
                s == null ? null : s.getLastSequence(),
                s == null ? null : s.getLastFingerprint(),
                s == null ? null : s.getVersion(),
                status,
                blocked,
                message,
                null);
    }

    public static String hash(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void require(boolean valid, String message) {
        if (!valid) throw new Conflict(message);
    }

    public void installed() {
        MesQualityGateSourceEvidence.scope();
        require(Objects.equals(1, states.schemaVersion()), "普通检验事件应用结构或守卫未就绪");
    }

    /**
     * 根/工序锁之后、判定接管之前锁生成记录，收件使用同一行互斥。
     */
    public void lockProcessEvidence(String code) {
        installed();
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) throw new CheckedException("质量依据互斥必须处于数量事务中");
        require(
                states.lockGenerations(
                                TenantContextHolder.getTenantId(),
                                FactoryContextHolder.getFactoryCode(),
                                code)
                        .size()
                        <= 5000,
                "工序检验记录超限");
    }

    public boolean controlled(String code) {
        installed();
        return states.controlled(
                TenantContextHolder.getTenantId(),
                FactoryContextHolder.getFactoryCode(),
                code)
                > 0;
    }

    public Resolution resolve(
            MesInspectionGenRecordEntity record,
            boolean controlled,
            Long candidateRecord,
            Long candidateEvent,
            Date time) {
        if (!controlled) return null;
        installed();
        if (!Boolean.TRUE.equals(record.getQualityEventManaged()))
            return new Resolution(
                    null, null, null, null, null, "PENDING", null, "本工序已进入事件执行，此阶段待正式原件", null);
        MesInspectionEventStateEntity state = null;
        try {
            state = binding(record, false);
            if (!"ACTIVE".equals(state.getStatus()))
                return missing(state, "CONFLICT", state.getLastError(), null);
            Resolution value;
            if (Objects.equals(record.getId(), candidateRecord))
                value = candidate(record, candidateEvent, time);
            else if (state.getLastSequence() == 0)
                return missing(state, "PENDING", "检验事件已收件，等待应用", null);
            else value = applied(record, state);
            Long latest =
                    states.latestSequence(
                            record.getId(), record.getTenantId(), record.getFactoryCode());
            require(latest != null && latest <= 5000, "检验收件范围不可核验");
            if (latest > value.sequence())
                return new Resolution(
                        null,
                        value.eventId(),
                        value.sequence(),
                        value.fingerprint(),
                        value.version(),
                        "PENDING",
                        null,
                        "存在待应用后件，暂不采用旧通过",
                        value.time());
            if (revisionHolds.held(record.getTaskCode()))
                return missing(state, "REVISION_HELD", "生产工单正在进行质检修订核验", null);
            return value;
        } catch (Conflict | CheckedException | MesQualityGateSourceEvidence.Rejected bad) {
            return missing(
                    state,
                    "CONFLICT",
                    bad.getMessage(),
                    state == null ? null : state.getBlockedEventId());
        }
    }

    public MesInspectionEventStateEntity binding(
            MesInspectionGenRecordEntity record, boolean lock) {
        installed();
        var s = states.state(record.getId(), record.getTenantId(), record.getFactoryCode(), lock);
        require(
                s != null
                        && Objects.equals(s.getTenantId(), record.getTenantId())
                        && Objects.equals(s.getFactoryCode(), record.getFactoryCode())
                        && Objects.equals(s.getInspectionId(), record.getInspectionId())
                        && Objects.equals(s.getTaskCode(), record.getTaskCode())
                        && Objects.equals(s.getProduceTaskCode(), record.getProduceTaskCode())
                        && s.getLastSequence() != null
                        && s.getLastSequence() >= 0
                        && s.getLastSequence() <= 5000
                        && s.getVersion() != null
                        && s.getVersion() >= 0,
                "事件接管状态与实际生成记录不一致");
        return s;
    }

    public Resolution candidate(MesInspectionGenRecordEntity record, Long eventId, Date time) {
        var s = binding(record, false);
        require("ACTIVE".equals(s.getStatus()), "事件流存在冲突，不能普通重试覆盖");
        var e =
                inbox.verified(
                        states.original(eventId, record.getTenantId(), record.getFactoryCode()));
        require(
                Boolean.TRUE.equals(record.getQualityEventManaged())
                        && Objects.equals(e.getGenRecordId(), record.getId())
                        && Objects.equals(e.getTaskId(), record.getInspectionId())
                        && e.getSequenceNo() == s.getLastSequence() + 1
                        && Objects.equals(e.getPreviousEventId(), s.getLastEventId())
                        && Objects.equals(e.getPreviousFingerprint(), s.getLastFingerprint()),
                "应用候选不是当前连续前件的下一原件");
        QmsInspectionProcedureTaskEntity q = inbox.source(e);
        sources.verify(record, q);
        QmsInspectionProcedureTaskEntity previous =
                s.getLastSequence() == 0 ? null : applied(record, s).task();
        transition(e, q, previous);
        require(time != null, "候选应用缺少实际事务时间");
        return new Resolution(
                q,
                e.getId(),
                e.getSequenceNo(),
                e.getFingerprint(),
                s.getVersion() + 1,
                "ACTIVE",
                null,
                "检验事件来源已核验",
                time);
    }

    /**
     * 历史明细仅核原件身份与创建来源，不用当前结论重写历史判定。
     */
    MesInspectionEventInboxEntity applicationOriginal(
            MesInspectionGenRecordEntity record, MesInspectionEventApplicationEntity application) {
        var original =
                inbox.verified(
                        states.original(
                                application.getId(),
                                record.getTenantId(),
                                record.getFactoryCode()));
        require(
                Objects.equals(application.getGenRecordId(), record.getId())
                        && Objects.equals(application.getInspectionId(), record.getInspectionId())
                        && Objects.equals(original.getGenRecordId(), record.getId())
                        && Objects.equals(original.getTaskId(), record.getInspectionId())
                        && Objects.equals(original.getSequenceNo(), application.getSequenceNo())
                        && Objects.equals(
                        original.getFingerprint(), application.getEventFingerprint())
                        && Objects.equals(
                        original.getPreviousEventId(), application.getPreviousEventId())
                        && Objects.equals(
                        original.getPreviousFingerprint(),
                        application.getPreviousFingerprint()),
                "应用回执不属于实际检验原件");
        sources.verifyOriginal(record, inbox.source(original));
        return original;
    }

    private Resolution applied(
            MesInspectionGenRecordEntity record, MesInspectionEventStateEntity s) {
        var e =
                inbox.verified(
                        states.original(
                                s.getLastEventId(), record.getTenantId(), record.getFactoryCode()));
        var a =
                states.application(
                        s.getLastEventId(), record.getTenantId(), record.getFactoryCode());
        require(
                a != null
                        && Objects.equals(a.getGenRecordId(), record.getId())
                        && Objects.equals(a.getInspectionId(), record.getInspectionId())
                        && Objects.equals(a.getSequenceNo(), s.getLastSequence())
                        && Objects.equals(e.getSequenceNo(), s.getLastSequence())
                        && Objects.equals(e.getGenRecordId(), record.getId())
                        && Objects.equals(a.getEventFingerprint(), e.getFingerprint())
                        && Objects.equals(s.getLastFingerprint(), e.getFingerprint())
                        && Objects.equals(a.getPreviousEventId(), e.getPreviousEventId())
                        && Objects.equals(a.getPreviousFingerprint(), e.getPreviousFingerprint())
                        && a.getAppliedAt() != null
                        && a.getDecisionSnapshot() != null
                        && a.getDecisionSnapshot().getBytes(StandardCharsets.UTF_8).length
                        <= 1048576
                        && Objects.equals(
                        hash(a.getDecisionSnapshot()), a.getDecisionFingerprint()),
                "已应用回执与不可变原件或决策快照不一致");
        try {
            var j = JSON.readTree(a.getDecisionSnapshot());
            require(
                    j.path("schemaVersion").isInt()
                            && j.get("schemaVersion").intValue() == 1
                            && e.getId().toString().equals(j.path("eventId").textValue())
                            && record.getId().toString().equals(j.path("genRecordId").textValue())
                            && e.getFingerprint().equals(j.path("eventFingerprint").textValue())
                            && j.path("decision").isObject(),
                    "应用决策快照身份不一致");
        } catch (Conflict bad) {
            throw bad;
        } catch (Exception bad) {
            throw new Conflict("应用决策快照不能解析");
        }
        var q = inbox.source(e);
        sources.verify(record, q);
        return new Resolution(
                q,
                e.getId(),
                e.getSequenceNo(),
                e.getFingerprint(),
                s.getVersion(),
                "ACTIVE",
                null,
                "采用已应用检验原件",
                a.getAppliedAt());
    }

    private void transition(
            MesInspectionEventInboxEntity e,
            QmsInspectionProcedureTaskEntity q,
            QmsInspectionProcedureTaskEntity previous) {
        String result = Objects.toString(q.getResult(), "");
        switch (e.getEventType()) {
            case "CREATE" -> require(result.isEmpty() && "WAIT".equals(q.getStatus()), "CREATE不能补造已判定事实");
            case "APPEND" -> require(
                    result.isEmpty() && Set.of("WAIT", "RUNNING").contains(q.getStatus()),
                    "判后追加须受控修订");
            case "RESULT" -> require(Set.of("QUALIFIED", "UN_QUALIFIED").contains(result), "RESULT缺少明确检验判定");
            case "DISPOSITION" -> require(
                    Set.of("UN_QUALIFIED", "RELEASE", "RECEIVE").contains(result),
                    "处置缺少合法接受结果");
            case "CANCELED" -> require(
                    "CANCELED".equals(result) && "FINISHED".equals(q.getStatus()),
                    "作废原件与状态不一致");
            case "EXEMPTION" -> require("EXEMPTION".equals(result), "免检原件与结论不一致");
            default -> throw new Conflict("应用动作不受支持");
        }
        if ("DISPOSITION".equals(e.getEventType())) {
            require(previous != null && e.getPreviousEventId() != null, "处置缺少连续原判定");
            var predecessor =
                    inbox.verified(
                            states.original(
                                    e.getPreviousEventId(), e.getTenantId(), e.getFactoryCode()));
            try {
                com.kitex.mom.qms.dto.inspection.QmsProcedureDispositionContract.verifyPredecessor(
                        JSON.readTree(e.getPayload()), predecessor.getPayload());
            } catch (Exception invalid) {
                throw new Conflict("处置原件与已应用前件不一致：" + invalid.getMessage());
            }
            return;
        }
        if (previous == null) return;
        String prior = Objects.toString(previous.getResult(), "");
        require(q.getNum() >= previous.getNum(), "检验批数量不能倒退");
        if ("APPEND".equals(e.getEventType()))
            require(!TERMINAL.contains(prior) && q.getNum() > previous.getNum(), "判后追加或无增长追加须受控修订");
        else require(q.getNum().equals(previous.getNum()), "批数量变化缺少真实追加原件");
        if ("QUALIFIED".equals(prior)
                && "UN_QUALIFIED".equals(result)
                && "RESULT".equals(e.getEventType())) {
            var authorization =
                    states.revisionAuthorization(
                            e.getGenRecordId(), e.getId(), e.getTenantId(), e.getFactoryCode());
            if (authorization != null) {
                String raw = Objects.toString(authorization.get("outcome"), null),
                        intent = Objects.toString(authorization.get("intent"), null);
                var proof =
                        com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionOutcomeContract.parse(
                                raw, intent);
                require(
                        "COMMITTED".equals(proof.decision())
                                && Objects.equals(
                                proof.sealFingerprint(),
                                authorization.get("sealFingerprint"))
                                && Objects.equals(proof.eventPayload(), e.getPayload()),
                        "正式修订授权与实际原文不一致");
                return;
            }
        }
        if (TERMINAL.contains(prior))
            require(
                    prior.equals(result) && !"APPEND".equals(e.getEventType()),
                    "已有判定改变、作废或追加须经受控修订");
        require(
                nz(q.getUsableNum()) >= nz(previous.getUsableNum())
                        && nz(q.getHandleNum()) >= nz(previous.getHandleNum()),
                "检验累计处置不能倒退，须受控修订");
    }

    public void attach(MesInspectionGenRecordEntity r, Resolution e) {
        r.setQualityEventControlled(e != null);
        if (e != null)
            r.setQualityEventId(e.eventId())
                    .setQualityEventSequence(e.sequence())
                    .setQualityEventFingerprint(e.fingerprint())
                    .setQualityEventVersion(e.version())
                    .setQualityEventState(e.status())
                    .setQualityBlockedEventId(e.blockedEvent())
                    .setQualityEventMessage(e.message())
                    .setQualityEventAppliedAt(e.time());
    }

    public MesQualityGateItemDTO item(
            MesInspectionGenRecordEntity r, QmsInspectionProcedureTaskEntity q) {
        String status =
                q == null
                        ? ("CONFLICT".equals(r.getQualityEventState()) ? "ERROR" : "PENDING")
                        : Set.of("QUALIFIED", "EXEMPTION", "RELEASE", "RECEIVE")
                        .contains(Objects.toString(q.getResult(), ""))
                        ? "PASSED"
                        : "UN_QUALIFIED".equals(q.getResult())
                        ? "FAILED"
                        : "CANCELED".equals(q.getResult()) ? "CANCELED" : "PENDING";
        boolean qty = q != null && !"CANCELED".equals(status);
        return new MesQualityGateItemDTO()
                .setRecordId(r.getId())
                .setTaskCode(r.getTaskCode())
                .setProduceTaskCode(r.getProduceTaskCode())
                .setInspectionType(r.getInspectionType())
                .setInspectionId(r.getInspectionId())
                .setSourceRequestId(r.getSourceRequestId())
                .setGenFlag(r.getGenFlag())
                .setGenRemark(r.getGenRemark())
                .setGateStatus(status)
                .setSyncMessage(r.getQualityEventMessage())
                .setInspectionStatus(q == null ? null : q.getStatus())
                .setInspectionResult(q == null ? null : q.getResult())
                .setQmsTotalNum(qty ? q.getNum() : null)
                .setQmsUsableNum(qty ? q.getUsableNum() : null)
                .setQmsHandleNum(qty ? q.getHandleNum() : null)
                .setQmsHandleResult(qty ? q.getHandleResult() : null)
                .setBlockedCount(r.getBlockedCount())
                .setGenTime(r.getGenTime())
                .setLastBlockedTime(r.getLastBlockedTime())
                .setLastSyncTime(
                        r.getQualityEventAppliedAt() == null
                                ? null
                                : LocalDateTime.ofInstant(
                                r.getQualityEventAppliedAt().toInstant(),
                                ZoneId.systemDefault()))
                .setQualityEventId(r.getQualityEventId())
                .setQualityEventSequence(r.getQualityEventSequence())
                .setQualityEventFingerprint(r.getQualityEventFingerprint())
                .setQualityEventVersion(r.getQualityEventVersion())
                .setQualityEventState(r.getQualityEventState())
                .setQualityBlockedEventId(r.getQualityBlockedEventId());
    }

    public record Resolution(
            QmsInspectionProcedureTaskEntity task,
            Long eventId,
            Long sequence,
            String fingerprint,
            Long version,
            String status,
            Long blockedEvent,
            String message,
            Date time) {
    }

    static class Conflict extends RuntimeException {
        Conflict(String message) {
            super(message);
        }
    }
}
