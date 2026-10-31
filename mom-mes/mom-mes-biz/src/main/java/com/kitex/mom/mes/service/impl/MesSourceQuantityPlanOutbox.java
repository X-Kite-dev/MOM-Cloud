package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesSourceQuantityPlanOutboxEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesSourceQuantityPlanOutboxMapper;
import com.kitex.mom.mes.mapper.MesReworkSourcePlanOutboxMapper;
import com.kitex.mom.oms.dto.OmsOrderLifeCycleDTO;
import com.kitex.mom.plan.feign.RemotePlnIssueService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 正式质量应用仅保存通知；独立投递使用原有PLAN请求幂等号恢复未知结果。
 */
@Service
@RequiredArgsConstructor
public class MesSourceQuantityPlanOutbox {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final MesSourceQuantityPlanOutboxMapper outboxes;
    private final MesReworkSourcePlanOutboxMapper reworks;
    private final RemotePlnIssueService plan;
    private java.time.Clock clock = java.time.Clock.systemUTC();

    static void background() {
        require(
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                        == null
                        && org.springframework.security.core.context.SecurityContextHolder
                        .getContext()
                        .getAuthentication()
                        == null,
                "PLAN来源通知只允许独立后台发送");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new CheckedException(message);
    }

    private Date now() {
        return Date.from(clock.instant());
    }

    void installed() {
        installed(false);
    }
    private void installed(boolean rework) {
        MesQualityGateSourceEvidence.scope();
        try {
            require(
                    Integer.valueOf(1)
                            .equals(rework ? reworks.schemaVersion() : outboxes.schemaVersion()),
                    "来源数量PLAN待发结构或守卫未就绪");
        } catch (org.springframework.dao.DataAccessException missing) {
            throw new CheckedException("来源数量PLAN待发结构未就绪，请完成对应数据库升级", missing);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void enqueue(
            MesTaskEntity task,
            String request,
            String fingerprint,
            List<OmsOrderLifeCycleDTO> callbacks) {
        enqueue(task, request, fingerprint, callbacks, false);
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void enqueueRework(
            MesTaskEntity task,
            String request,
            String fingerprint,
            List<OmsOrderLifeCycleDTO> callbacks) {
        enqueue(task, request, fingerprint, callbacks, true);
    }

    private void enqueue(
            MesTaskEntity task,
            String request,
            String fingerprint,
            List<OmsOrderLifeCycleDTO> callbacks,
            boolean rework) {
        installed(rework);
        require(
                task != null
                        && task.getId() != null
                        && task.getId() > 0
                        && Objects.equals(task.getTenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(
                                task.getFactoryCode(), FactoryContextHolder.getFactoryCode()),
                "PLAN通知缺少实际工单绑定");
        require(
                callbacks != null && !callbacks.isEmpty() && callbacks.size() <= 5000,
                "PLAN来源通知条数无效");
        String raw = MesSourcePlanPayload.encode(task, request, callbacks);
        require(raw.getBytes(StandardCharsets.UTF_8).length <= 1048576, "PLAN来源通知超过1MiB");
        var existing =
                rework
                        ? reworks.original(task.getTenantId(), task.getFactoryCode(), request)
                        : outboxes.original(task.getTenantId(), task.getFactoryCode(), request);
        if (!existing.isEmpty()) {
            require(
                    existing.size() == 1
                            && Objects.equals(existing.get(0).getRequestFingerprint(), fingerprint)
                            && Objects.equals(existing.get(0).getPayload(), raw),
                    "来源数量请求已登记不同PLAN原件");
            return;
        }
        var now = now();
        var row =
                new MesSourceQuantityPlanOutboxEntity()
                        .setId(IdUtil.getSnowflakeNextId())
                        .setTenantId(task.getTenantId())
                        .setFactoryCode(task.getFactoryCode())
                        .setRequestId(request)
                        .setRequestFingerprint(fingerprint)
                        .setTaskId(task.getId())
                        .setTaskCode(task.getCode())
                        .setPayload(raw)
                        .setPayloadFingerprint(MesInspectionEventProjection.hash(raw))
                        .setStatus("PENDING")
                        .setAttemptCount(0L)
                        .setCreatedAt(now)
                        .setNextAttemptAt(now);
        if (rework) readRework(row);
        require((rework ? reworks.insert(row) : outboxes.insert(row)) == 1, "来源数量PLAN待发未保存，已整体回滚");
    }

    public void requireAbsentRework(MesTaskEntity task, String request) {
        installed(true);
        require(
                reworks.original(task.getTenantId(), task.getFactoryCode(), request).isEmpty(),
                "返工PLAN已有原件但来源分录缺失，禁止补造");
    }

    public void requireExistingRework(MesTaskEntity task, String request, String fingerprint) {
        installed(true);
        var rows = reworks.original(task.getTenantId(), task.getFactoryCode(), request);
        require(
                rows.size() == 1
                        && Objects.equals(rows.get(0).getTaskId(), task.getId())
                        && Objects.equals(rows.get(0).getRequestFingerprint(), fingerprint),
                "返工来源已记账但缺同一PLAN原件，禁止补造");
        readRework(rows.get(0));
    }

    private List<OmsOrderLifeCycleDTO> readRework(MesSourceQuantityPlanOutboxEntity row) {
        require(
                row.getPayload() != null
                        && row.getPayload().getBytes(StandardCharsets.UTF_8).length <= 1048576
                        && Objects.equals(
                                MesInspectionEventProjection.hash(row.getPayload()),
                                row.getPayloadFingerprint()),
                "返工PLAN原文指纹损坏");
        var facts = reworks.events(row.getTenantId(), row.getFactoryCode(), row.getRequestId());
        require(facts != null && !facts.isEmpty() && facts.size() <= 5000, "返工PLAN实际来源缺失或超限");
        var actual =
                reworks.rework(
                        row.getTenantId(), row.getFactoryCode(), facts.get(0).getSourceReference());
        require(actual != null && actual.size() == 1, "返工PLAN实际返工单缺失或不唯一");
        try {
            return MesSourcePlanPayload.rework(
                    row,
                    reworks.source(row.getTenantId(), row.getFactoryCode(), row.getTaskId()),
                    facts,
                    actual.get(0));
        } catch (CheckedException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new CheckedException("返工PLAN原文结构损坏", invalid);
        }
    }

    public void requireExisting(MesTaskEntity task, String request, String fingerprint) {
        installed();
        var rows = outboxes.original(task.getTenantId(), task.getFactoryCode(), request);
        require(
                rows.size() == 1
                        && Objects.equals(rows.get(0).getTaskId(), task.getId())
                        && Objects.equals(rows.get(0).getRequestFingerprint(), fingerprint),
                "已有来源数量事实缺少同一PLAN待发原件，禁止补造");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean deliver(Long id) {
        return deliver(id, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean deliverRework(Long id) {
        return deliver(id, true);
    }

    private boolean deliver(Long id, boolean rework) {
        background();
        installed(rework);
        var row =
                rework
                        ? reworks.locked(
                                TenantContextHolder.getTenantId(),
                                FactoryContextHolder.getFactoryCode(),
                                id)
                        : outboxes.locked(
                                TenantContextHolder.getTenantId(),
                                FactoryContextHolder.getFactoryCode(),
                                id);
        if (row == null || "RECEIVED".equals(row.getStatus())) return false;
        require(
                row.getAttemptCount() != null
                        && row.getAttemptCount() >= 0
                        && row.getNextAttemptAt() != null
                        && Set.of("PENDING", "RETRY_WAIT")
                                .contains(Objects.toString(row.getStatus(), "")),
                "PLAN待发进度结构无效");
        if (row.getNextAttemptAt().after(now())) return false;
        long previous = row.getAttemptCount();
        row.setAttemptCount(Math.addExact(previous, 1));
        try {
            require(
                    row.getPayload() != null
                            && row.getPayload().getBytes(StandardCharsets.UTF_8).length <= 1048576
                            && Objects.equals(
                                    MesInspectionEventProjection.hash(row.getPayload()),
                                    row.getPayloadFingerprint()),
                    "PLAN来源通知原文损坏");
            var body = JSON.readTree(row.getPayload());
            require(
                    body.path("schemaVersion").asInt() == 1
                            && body.path("tenantId").asText().equals(row.getTenantId().toString())
                            && body.path("factoryCode").asText().equals(row.getFactoryCode())
                            && body.path("taskId").asText().equals(row.getTaskId().toString())
                            && body.path("taskCode").asText().equals(row.getTaskCode())
                            && body.path("requestId").asText().equals(row.getRequestId())
                            && body.path("callbacks").isArray()
                            && body.path("callbacks").size() > 0
                            && body.path("callbacks").size() <= 5000,
                    "PLAN来源通知身份无效");
            var callbacks =
                    rework
                            ? readRework(row)
                            : JSON.readerForListOf(OmsOrderLifeCycleDTO.class)
                                    .<List<OmsOrderLifeCycleDTO>>readValue(body.path("callbacks"));
            var result = plan.edit(callbacks);
            require(
                    result != null && result.isOk() && Boolean.TRUE.equals(result.getData()),
                    "PLAN未确认来源数量通知");
            row.setStatus("RECEIVED")
                    .setReceivedAt(now())
                    .setNextAttemptAt(null)
                    .setLastError(null);
        } catch (Exception failure) {
            row.setStatus("RETRY_WAIT")
                    .setReceivedAt(null)
                    .setNextAttemptAt(
                            new Date(
                                    clock.millis()
                                            + Math.min(
                                                    3600000L,
                                                    30000L * (1L << Math.min(7L, previous)))))
                    .setLastError("PLAN来源数量未确认：" + failure.getClass().getSimpleName());
        }
        require(
                (rework ? reworks.progress(row, previous) : outboxes.progress(row, previous)) == 1,
                "PLAN通知投递进度未保存，原件将按原请求重试");
        return "RECEIVED".equals(row.getStatus());
    }

    public List<MesSourceQuantityPlanOutboxEntity> candidates() {
        return candidates(false);
    }
    public List<MesSourceQuantityPlanOutboxEntity> reworkCandidates() {
        return candidates(true);
    }
    private List<MesSourceQuantityPlanOutboxEntity> candidates(boolean rework) {
        background();
        require(
                !org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive(),
                "跨组织PLAN候选只允许独立后台查询");
        require(
                Integer.valueOf(1)
                        .equals(rework ? reworks.schemaVersion() : outboxes.schemaVersion()),
                "PLAN来源通知结构未就绪");
        return rework ? reworks.due(now()) : outboxes.due(now());
    }
}
