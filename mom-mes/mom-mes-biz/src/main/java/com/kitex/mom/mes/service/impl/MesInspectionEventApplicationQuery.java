package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesInspectionEventApplicationEntity;
import com.kitex.mom.mes.entity.MesInspectionEventStateEntity;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import com.kitex.mom.mes.entity.MesSourceQuantityPlanOutboxEntity;
import com.kitex.mom.mes.mapper.MesInspectionEventStateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 实名工厂只读视图；不提供覆盖冲突、跳件或改原文的入口。
 */
@Service
@RequiredArgsConstructor
public class MesInspectionEventApplicationQuery {
    private static final String WORKBENCH = "MES_INSPECTION_EVENT_WORKBENCH_V1";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(
                            com.fasterxml.jackson.core.JsonParser.Feature
                                    .STRICT_DUPLICATE_DETECTION)
                    .enable(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_TRAILING_TOKENS)
                    .enable(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .USE_BIG_DECIMAL_FOR_FLOATS)
                    .setNodeFactory(
                            com.fasterxml.jackson.databind.node.JsonNodeFactory
                                    .withExactBigDecimals(true));
    private final MesInspectionEventStateMapper states;
    private final MesInspectionEventProjection projection;
    private final MesActualFactoryAccessService access;
    private final com.kitex.mom.mes.mapper.MesSourceQuantityPlanOutboxMapper plans;

    /**
     * 仅规范化HTTP只读副本；原始快照字节和指纹保持不变。
     */
    private static com.fasterxml.jackson.databind.JsonNode decisionView(
            com.fasterxml.jackson.databind.JsonNode original) {
        var view = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
        for (var item : view.path("items")) {
            require(item.isObject(), "应用参与项不是有效对象");
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) item;
            for (String name :
                    List.of(
                            "recordId",
                            "inspectionId",
                            "qualityEventId",
                            "qualityBlockedEventId")) {
                var id = object.get(name);
                if (id == null || id.isNull()) continue;
                require(id.isIntegralNumber() || id.isTextual(), "应用参与对象主键类型无效");
                String text = id.asText();
                require(
                        text.matches("[1-9][0-9]{0,18}")
                                && new java.math.BigInteger(text)
                                .compareTo(
                                        java.math.BigInteger.valueOf(
                                                Long.MAX_VALUE))
                                <= 0,
                        "应用参与对象主键超出范围");
                object.put(name, text);
            }
        }
        for (String name :
                List.of(
                        "reportedQuantity",
                        "releasedQuantity",
                        "pendingQuantity",
                        "rejectedQuantity")) {
            var quantity = view.get(name);
            if (quantity == null || quantity.isNull()) continue;
            require(quantity.isNumber() || quantity.isTextual(), "应用累计数量类型无效");
            String text = quantity.asText();
            if (text.contains(".") && !text.contains("E") && !text.contains("e"))
                text = text.replaceFirst("0+$", "").replaceFirst("\\.$", "");
            view.put(name, text);
        }
        return view;
    }

    private static String actor() {
        return com.kitex.common.security.util.SecurityUtils.getUser().getId().toString();
    }

    private static void pageRange(long after, int limit, long maximum) {
        require(after >= 0 && after <= maximum && limit >= 1 && limit <= 50, "核对查询分页范围无效");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new CheckedException(message);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View get(Long recordId, long after, int limit) {
        access.require("read");
        projection.installed();
        if (recordId == null
                || recordId <= 0
                || after < 0
                || after > 5000
                || limit < 1
                || limit > 100) throw new CheckedException("应用记录查询范围无效");
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        var r = states.generation(recordId, tenant, factory);
        if (r == null) throw new CheckedException("当前组织不存在实际检验生成记录");
        var state = states.state(recordId, tenant, factory, false);
        var health =
                projection.resolve(
                        r, projection.controlled(r.getProduceTaskCode()), null, null, null);
        var footprint = states.applicationFootprint(recordId, tenant, factory);
        long total = number(footprint, "event_count"), bytes = number(footprint, "snapshot_bytes");
        if (total > 5000 || bytes > 33554432L) throw new CheckedException("应用记录超过治理上限");
        var rows = states.applicationsPage(recordId, tenant, factory, after, limit);
        for (var row : rows)
            if (row.getDecisionSnapshot() == null
                    || row.getDecisionSnapshot()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    .length
                    > 1048576
                    || !Objects.equals(
                    MesInspectionEventProjection.hash(row.getDecisionSnapshot()),
                    row.getDecisionFingerprint())) throw new CheckedException("应用决策快照损坏");
        return new View(
                state,
                states.latestSequence(recordId, tenant, factory),
                health == null ? "LEGACY" : health.status(),
                health == null ? null : health.message(),
                total,
                rows,
                rows.isEmpty() ? after : rows.get(rows.size() - 1).getSequenceNo());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<MesSourceQuantityPlanOutboxEntity> planDeliveries(
            Long recordId, long after, int limit) {
        access.require("read");
        projection.installed();
        if (recordId == null || recordId <= 0 || after < 0 || limit < 1 || limit > 100)
            throw new CheckedException("PLAN通知查询范围无效");
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        var record = states.generation(recordId, tenant, factory);
        if (record == null) throw new CheckedException("当前组织不存在实际检验记录");
        var root = states.rootIds(tenant, factory, record.getTaskCode());
        if (root.size() != 1 || !Integer.valueOf(1).equals(plans.schemaVersion()))
            throw new CheckedException("PLAN通知实际工单或结构不可核验");
        return plans.page(tenant, factory, root.get(0), after, limit);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Overview overview(Long recordId, long after, int limit) {
        var record = readRecord(recordId);
        pageRange(after, limit, 5000);
        Long tenant = record.getTenantId();
        String factory = record.getFactoryCode();
        var state = states.state(recordId, tenant, factory, false);
        var health =
                projection.resolve(
                        record,
                        projection.controlled(record.getProduceTaskCode()),
                        null,
                        null,
                        null);
        var footprint = states.applicationFootprint(recordId, tenant, factory);
        long total = number(footprint, "event_count"), bytes = number(footprint, "snapshot_bytes");
        long applied = state == null ? 0 : state.getLastSequence();
        Long received = states.latestSequence(recordId, tenant, factory);
        require(
                total == applied
                        && total <= 5000
                        && bytes <= 33554432L
                        && received != null
                        && received >= applied
                        && received <= 5000,
                "检验应用范围或容量不可核验");
        var rows = states.applicationSummariesPage(recordId, tenant, factory, after, limit);
        require(rows.size() == Math.min(limit, Math.max(0, total - after)), "检验应用摘要数量不一致");
        long cursor = after;
        for (var row : rows) {
            require(
                    row.getEventId() != null
                            && row.getSequenceNo() != null
                            && row.getSequenceNo() == cursor + 1
                            && row.getAppliedAt() != null,
                    "检验应用摘要身份不连续");
            cursor = row.getSequenceNo();
        }
        String gate = null;
        if (health != null) {
            projection.attach(record, health);
            gate = projection.item(record, health.task()).getGateStatus();
        }
        return new Overview(
                WORKBENCH,
                tenant.toString(),
                factory,
                actor(),
                recordId.toString(),
                record.getTaskCode(),
                record.getProduceTaskCode(),
                record.getInspectionType(),
                record.getInspectionId() == null ? null : record.getInspectionId().toString(),
                health == null ? "LEGACY" : health.status(),
                gate,
                health == null ? null : health.message(),
                state == null || state.getBlockedEventId() == null
                        ? null
                        : state.getBlockedEventId().toString(),
                received,
                applied,
                total,
                rows,
                cursor,
                cursor < total);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApplicationDetail applicationDetail(Long recordId, Long eventId) {
        var record = readRecord(recordId);
        require(eventId != null && eventId > 0, "应用事件号无效");
        var application =
                states.applicationBounded(
                        eventId, recordId, record.getTenantId(), record.getFactoryCode());
        var state = states.state(recordId, record.getTenantId(), record.getFactoryCode(), false);
        require(
                application != null
                        && state != null
                        && application.getSequenceNo() != null
                        && application.getSequenceNo() > 0
                        && application.getSequenceNo() <= state.getLastSequence()
                        && application.getAppliedAt() != null,
                "不存在该已应用事件或原文超限");
        require(
                application.getDecisionSnapshot() != null
                        && Objects.equals(
                        MesInspectionEventProjection.hash(
                                application.getDecisionSnapshot()),
                        application.getDecisionFingerprint()),
                "应用决策指纹不一致");
        var original = projection.applicationOriginal(record, application);
        com.fasterxml.jackson.databind.JsonNode body;
        try {
            body = JSON.readTree(application.getDecisionSnapshot());
        } catch (Exception invalid) {
            throw new CheckedException("应用决策原文不能解析", invalid);
        }
        require(
                body.path("schemaVersion").isInt()
                        && body.path("schemaVersion").intValue() == 1
                        && eventId.toString().equals(body.path("eventId").textValue())
                        && recordId.toString().equals(body.path("genRecordId").textValue())
                        && application
                        .getEventFingerprint()
                        .equals(body.path("eventFingerprint").textValue())
                        && body.path("decision").isObject()
                        && body.path("decision").path("passed").isBoolean()
                        && body.path("decision").path("items").isArray()
                        && body.path("decision").path("items").size() <= 5000,
                "应用决策身份或结构无效");
        var summary =
                new com.kitex.mom.mes.dto.MesInspectionEventSummaryDTO()
                        .setEventId(eventId)
                        .setSequenceNo(application.getSequenceNo())
                        .setEventType(original.getEventType())
                        .setEventFingerprint(application.getEventFingerprint())
                        .setPreviousEventId(application.getPreviousEventId())
                        .setPreviousFingerprint(application.getPreviousFingerprint())
                        .setAppliedAt(application.getAppliedAt())
                        .setDecisionFingerprint(application.getDecisionFingerprint());
        return new ApplicationDetail(
                WORKBENCH,
                record.getTenantId().toString(),
                record.getFactoryCode(),
                actor(),
                recordId.toString(),
                summary,
                decisionView(body.get("decision")));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PlanPage planPage(Long recordId, long after, int limit) {
        var record = readRecord(recordId);
        pageRange(after, limit, Long.MAX_VALUE);
        var root =
                states.rootIds(record.getTenantId(), record.getFactoryCode(), record.getTaskCode());
        require(
                root.size() == 1 && Integer.valueOf(1).equals(plans.schemaVersion()),
                "PLAN通知实际工单或结构不可核验");
        var rows =
                plans.page(
                        record.getTenantId(),
                        record.getFactoryCode(),
                        root.get(0),
                        after,
                        limit + 1);
        require(rows.size() <= limit + 1, "PLAN通知摘要超限");
        boolean more = rows.size() > limit;
        rows = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        long cursor = after;
        for (var row : rows) {
            require(
                    row.getId() != null
                            && row.getId() > cursor
                            && Objects.equals(row.getTaskId(), root.get(0)),
                    "PLAN通知摘要身份无效");
            cursor = row.getId();
        }
        return new PlanPage(
                WORKBENCH,
                record.getTenantId().toString(),
                record.getFactoryCode(),
                actor(),
                recordId.toString(),
                rows,
                Long.toString(cursor),
                more);
    }

    private MesInspectionGenRecordEntity readRecord(Long id) {
        access.require("read");
        projection.installed();
        require(id != null && id > 0, "检验生成记录号无效");
        var record =
                states.generation(
                        id,
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode());
        require(record != null, "当前组织不存在实际检验记录");
        return record;
    }

    private long number(Map<String, Object> values, String name) {
        Object n = values == null ? null : values.get(name);
        if (!(n instanceof Number) || ((Number) n).longValue() < 0)
            throw new CheckedException("应用记录容量不可核验");
        return ((Number) n).longValue();
    }

    public record Overview(
            String contractVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String recordId,
            String taskCode,
            String produceTaskCode,
            String inspectionType,
            String inspectionId,
            String projectionState,
            String currentGateStatus,
            String message,
            String blockedEventId,
            long receivedThrough,
            long appliedThrough,
            long total,
            List<com.kitex.mom.mes.dto.MesInspectionEventSummaryDTO> items,
            long nextAfter,
            boolean hasMore) {
    }

    public record ApplicationDetail(
            String contractVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String recordId,
            com.kitex.mom.mes.dto.MesInspectionEventSummaryDTO summary,
            com.fasterxml.jackson.databind.JsonNode decision) {
    }

    public record PlanPage(
            String contractVersion,
            String tenantId,
            String factoryCode,
            String actorId,
            String recordId,
            List<MesSourceQuantityPlanOutboxEntity> items,
            String nextAfter,
            boolean hasMore) {
    }

    public record View(
            MesInspectionEventStateEntity state,
            Long receivedThrough,
            String projectionState,
            String message,
            long total,
            List<MesInspectionEventApplicationEntity> applications,
            long nextAfter) {
    }
}
