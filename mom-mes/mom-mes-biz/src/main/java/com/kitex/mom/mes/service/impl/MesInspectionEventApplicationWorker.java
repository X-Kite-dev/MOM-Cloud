package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.jackson.MomJavaTimeModule;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesInspectionEventApplicationEntity;
import com.kitex.mom.mes.entity.MesInspectionEventInboxEntity;
import com.kitex.mom.mes.entity.MesInspectionEventStateEntity;
import com.kitex.mom.mes.mapper.MesInspectionEventStateMapper;
import com.kitex.mom.mes.service.MesQualityQuantityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 本域统一根锁中的原件消费、数量/库存、应用回执和游标事务。
 */
@Service
@RequiredArgsConstructor
public class MesInspectionEventApplicationWorker {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new MomJavaTimeModule())
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final MesInspectionEventStateMapper states;
    private final MesInspectionEventInboxService inbox;
    private final MesInspectionEventProjection projection;
    private final MesQualityGateSourceEvidence sources;
    private final MesQualityQuantityService quantities;
    private final MesProductionTaskLock taskLock;

    private static String trim(String s) {
        return s == null ? "检验事件应用来源冲突" : s.substring(0, Math.min(500, s.length()));
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean consume(Long recordId) {
        return consumeLocked(recordId, null);
    }

    boolean applyRevision(Long recordId, Long eventId, Date time) {
        require(
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive()
                        && time != null,
                "正式修订应用必须加入实际事务");
        require(
                states.revisionAuthorization(
                        recordId,
                        eventId,
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode())
                        != null,
                "缺少正式修订原件授权");
        return consumeLocked(recordId, time);
    }

    private boolean consumeLocked(Long recordId, Date appliedTime) {
        projection.installed();
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        var observed = states.generation(recordId, tenant, factory);
        if (observed == null || !Boolean.TRUE.equals(observed.getQualityEventManaged()))
            return false;
        var process = sources.parent(observed.getProduceTaskCode());
        var root = sources.root(process);
        taskLock.lock(root.getCode());
        require(
                states.lockRoot(tenant, factory, root.getCode()).equals(List.of(root.getId())),
                "正式应用根工单已变化");
        require(
                states.lockProcess(tenant, factory, process.getCode())
                        .equals(List.of(process.getId())),
                "正式应用工序已变化");
        var records = states.lockGenerations(tenant, factory, process.getCode());
        require(records.size() <= 5000, "工序检验记录超限");
        var record =
                records.stream()
                        .filter(r -> Objects.equals(r.getId(), recordId))
                        .findFirst()
                        .orElse(null);
        require(
                record != null
                        && Objects.equals(record.getInspectionId(), observed.getInspectionId())
                        && Objects.equals(record.getTaskCode(), root.getCode())
                        && Boolean.TRUE.equals(record.getQualityEventManaged()),
                "检验生成绑定已变化");
        var state = projection.binding(record, true);
        if (!"ACTIVE".equals(state.getStatus())) return false;
        MesInspectionEventInboxEntity event = null;
        MesInspectionEventProjection.Resolution candidate;
        Date time = appliedTime == null ? new Date() : new Date(appliedTime.getTime());
        try {
            var chain = inbox.localChain(record);
            if (chain.size() == state.getLastSequence()) return false;
            require(chain.size() > state.getLastSequence(), "收件链少于已应用游标");
            event = chain.get(Math.toIntExact(state.getLastSequence()));
            candidate = projection.candidate(record, event.getId(), time);
        } catch (MesInspectionEventProjection.Conflict
                 | CheckedException
                 | MesQualityGateSourceEvidence.Rejected conflict) {
            Long blocked = event == null ? null : event.getId();
            require(
                    states.update(
                            null,
                            new LambdaUpdateWrapper<MesInspectionEventStateEntity>()
                                    .eq(MesInspectionEventStateEntity::getId, state.getId())
                                    .eq(MesInspectionEventStateEntity::getTenantId, tenant)
                                    .eq(
                                            MesInspectionEventStateEntity::getFactoryCode,
                                            factory)
                                    .eq(
                                            MesInspectionEventStateEntity::getVersion,
                                            state.getVersion())
                                    .set(
                                            MesInspectionEventStateEntity::getStatus,
                                            "CONFLICT")
                                    .set(
                                            MesInspectionEventStateEntity
                                                    ::getBlockedEventId,
                                            blocked)
                                    .set(
                                            MesInspectionEventStateEntity::getLastError,
                                            trim(conflict.getMessage()))
                                    .set(
                                            MesInspectionEventStateEntity::getVersion,
                                            state.getVersion() + 1)
                                    .set(MesInspectionEventStateEntity::getUpdatedAt, time))
                            == 1,
                    "事件冲突未保存");
            return false;
        }
        var footprint = states.applicationFootprint(recordId, tenant, factory);
        require(
                footprint != null
                        && footprint.get("event_count") instanceof Number
                        && footprint.get("snapshot_bytes") instanceof Number,
                "应用账容量不可核验");
        long appCount = ((Number) footprint.get("event_count")).longValue(),
                appBytes = ((Number) footprint.get("snapshot_bytes")).longValue();
        require(
                appCount == state.getLastSequence() && appBytes >= 0 && appBytes <= 33554432L,
                "应用账完整范围或容量不一致");
        var decision = quantities.reconcileEvent(process.getCode(), recordId, event.getId(), time);
        Map<String, Object> envelope = new TreeMap<>();
        envelope.put("schemaVersion", 1);
        envelope.put("eventId", event.getId().toString());
        envelope.put("genRecordId", recordId.toString());
        envelope.put("eventFingerprint", event.getFingerprint());
        envelope.put("decision", decision);
        String raw;
        try {
            raw = JSON.writeValueAsString(envelope);
        } catch (Exception bad) {
            throw new CheckedException("应用决策原件无法生成", bad);
        }
        require(raw.getBytes(StandardCharsets.UTF_8).length <= 1048576, "应用决策快照超过1MiB");
        require(
                appBytes + raw.getBytes(StandardCharsets.UTF_8).length <= 33554432L,
                "应用决策原文累计超过32MiB，请先治理");
        var receipt =
                new MesInspectionEventApplicationEntity()
                        .setId(event.getId())
                        .setTenantId(tenant)
                        .setFactoryCode(factory)
                        .setGenRecordId(recordId)
                        .setInspectionId(record.getInspectionId())
                        .setSequenceNo(event.getSequenceNo())
                        .setEventFingerprint(event.getFingerprint())
                        .setPreviousEventId(event.getPreviousEventId())
                        .setPreviousFingerprint(event.getPreviousFingerprint())
                        .setDecisionSnapshot(raw)
                        .setDecisionFingerprint(MesInspectionEventProjection.hash(raw))
                        .setAppliedAt(time);
        require(states.append(receipt) == 1, "正式应用回执未保存，已整体回滚");
        var evidence = projection.resolve(record, true, recordId, event.getId(), time);
        projection.attach(record, evidence);
        require(
                states.project(tenant, factory, projection.item(record, evidence.task())) == 1,
                "正式事件缓存未保存，已整体回滚");
        require(
                states.update(
                        null,
                        new LambdaUpdateWrapper<MesInspectionEventStateEntity>()
                                .eq(MesInspectionEventStateEntity::getId, state.getId())
                                .eq(MesInspectionEventStateEntity::getTenantId, tenant)
                                .eq(MesInspectionEventStateEntity::getFactoryCode, factory)
                                .eq(
                                        MesInspectionEventStateEntity::getVersion,
                                        state.getVersion())
                                .eq(MesInspectionEventStateEntity::getStatus, "ACTIVE")
                                .set(
                                        MesInspectionEventStateEntity::getLastSequence,
                                        event.getSequenceNo())
                                .set(
                                        MesInspectionEventStateEntity::getLastEventId,
                                        event.getId())
                                .set(
                                        MesInspectionEventStateEntity::getLastFingerprint,
                                        event.getFingerprint())
                                .set(
                                        MesInspectionEventStateEntity::getVersion,
                                        state.getVersion() + 1)
                                .set(MesInspectionEventStateEntity::getUpdatedAt, time))
                        == 1,
                "正式应用游标未保存，已整体回滚");
        return true;
    }
}
