package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionHistorySupport.require;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.jackson.MomJavaTimeModule;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.qms.entity.QmsInspectionEventEntity;
import com.kitex.mom.qms.mapper.QmsInspectionEventMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 原历史写事务中的永久事件采集；与传输、处置许可和首检指标资格分别管理。 */
@Service
@RequiredArgsConstructor
public class QmsInspectionEventService {
    private static final long MAX_CHAIN_BYTES = 8L * 1024 * 1024;
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new MomJavaTimeModule())
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Set<String> TYPES = Set.of("PROCEDURE", "PRODUCT");
    private static final Set<String> ACTIONS =
            Set.of("CREATE", "APPEND", "RESULT", "CANCELED", "EXEMPTION", "DISPOSITION");
    private final QmsInspectionEventMapper events;
    private final QmsInspectionHistorySupport access;
    private final QmsInspectionEventPublisher publisher;

    QmsInspectionEventEntity capture(String type, Long historyId) {
        return capture(type, historyId, null, null);
    }

    QmsInspectionEventEntity captureDisposition(
            Long historyId, String dispositionPayload, Long eventId) {
        require(dispositionPayload != null && eventId != null && eventId > 0, "处置缺少原件或预分配事件标识");
        return capture("PROCEDURE", historyId, dispositionPayload, eventId);
    }

    private QmsInspectionEventEntity capture(
            String type, Long historyId, String dispositionPayload, Long assignedId) {
        require(TransactionSynchronizationManager.isActualTransactionActive(), "检验事件必须加入原业务事务");
        access.requireScope();
        require(
                TYPES.contains(Objects.toString(type, "")) && historyId != null && historyId > 0,
                "检验事件来源无效");
        installed();
        Map<String, Object> history =
                "PROCEDURE".equals(type)
                        ? events.procedureHistory(
                                TenantContextHolder.getTenantId(),
                                FactoryContextHolder.getFactoryCode(),
                                historyId)
                        : events.productHistory(
                                TenantContextHolder.getTenantId(),
                                FactoryContextHolder.getFactoryCode(),
                                historyId);
        require(history != null, "当前组织不存在待采集的检验历史");
        JsonNode fact = JSON.valueToTree(history);
        Long taskId =
                fact.path("taskId").isIntegralNumber() ? fact.path("taskId").longValue() : null;
        Map<String, Object> parent = parent(type, taskId, true);
        require(history.get("time") instanceof Date, "检验历史原始时间不可核验");
        Date time = new Date(((Date) history.get("time")).getTime());
        String action = fact.path("type").asText();
        require(time != null && ACTIONS.contains(action), "检验事件缺少原始时间或合法动作");
        require("DISPOSITION".equals(action) == (dispositionPayload != null), "处置必须从正式原件入口采集");
        if (dispositionPayload == null) validateSource(fact, JSON.valueToTree(parent), action);
        else {
            var proof =
                    com.kitex.mom.qms.dto.inspection.QmsProcedureDispositionContract.parse(
                            dispositionPayload);
            require(
                    "PROCEDURE".equals(type)
                            && fact.path("num").isIntegralNumber()
                            && fact.path("num").intValue() == proof.quantity()
                            && (fact.path("qualifiedStatus").isNull()
                                    || fact.path("qualifiedStatus").isMissingNode()),
                    "处置历史与原件数量不一致");
        }
        var chain = chain(type, taskId);
        var existing =
                chain.stream()
                        .filter(row -> Objects.equals(row.getHistoryId(), historyId))
                        .findFirst()
                        .orElse(null);
        if (existing != null) {
            require(
                    Objects.equals(existing.getEventType(), action)
                            && existing.getOccurredAt().getTime() == time.getTime(),
                    "同一检验历史的动作或时间已变化，拒绝换件");
            require(
                    existing.getPayload()
                            .equals(payload(existing, fact, parent, dispositionPayload)),
                    "同一检验历史的事件原文已变化，拒绝换件");
            publisher.enqueue(chain);
            return existing;
        }
        var head = chain.isEmpty() ? null : chain.get(chain.size() - 1);
        long sequence = head == null ? 1 : Math.addExact(head.getSequenceNo(), 1);
        require(sequence <= 5000, "单任务检验事件超过5000条，请先治理");
        var event =
                new QmsInspectionEventEntity()
                        .setId(assignedId == null ? IdWorker.getId() : assignedId)
                        .setTenantId(TenantContextHolder.getTenantId())
                        .setFactoryCode(FactoryContextHolder.getFactoryCode())
                        .setInspectionType(type)
                        .setTaskId(taskId)
                        .setHistoryId(historyId)
                        .setEventType(action)
                        .setSequenceNo(sequence)
                        .setOccurredAt(time)
                        .setPreviousEventId(head == null ? null : head.getId())
                        .setPreviousFingerprint(head == null ? null : head.getFingerprint());
        String raw = payload(event, fact, parent, dispositionPayload);
        if (dispositionPayload != null) {
            require(head != null, "处置缺少原检验判定");
            try {
                com.kitex.mom.qms.dto.inspection.QmsProcedureDispositionContract.verifyPredecessor(
                        JSON.readTree(raw), head.getPayload());
            } catch (Exception invalid) {
                throw new CheckedException("处置前件不可核验：" + invalid.getMessage(), invalid);
            }
        }
        require(raw.getBytes(StandardCharsets.UTF_8).length <= 65536, "检验事件原件超过64KiB，拒绝截断");
        require(
                chain.stream()
                                        .mapToLong(
                                                row ->
                                                        row.getPayload()
                                                                .getBytes(StandardCharsets.UTF_8)
                                                                .length)
                                        .sum()
                                + raw.getBytes(StandardCharsets.UTF_8).length
                        <= MAX_CHAIN_BYTES,
                "单任务检验事件原文累计超过8MiB，请先治理");
        event.setPayload(raw).setFingerprint(hash(raw));
        require(events.insert(event) == 1, "检验事件保存失败，业务与历史已回滚");
        var complete = new ArrayList<>(chain);
        complete.add(event);
        publisher.enqueue(complete);
        return event;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<QmsInspectionEventEntity> events(String type, Long taskId) {
        access.requireRead();
        require(TYPES.contains(Objects.toString(type, "")), "检验事件类型无效");
        installed();
        parent(type, taskId, false);
        return chain(type, taskId);
    }

    /** 仅本域已核权恢复入口使用；不经HTTP暴露，不重复跨服务核权持父锁。 */
    List<QmsInspectionEventEntity> originals(String type, Long taskId) {
        access.requireScope();
        require(TYPES.contains(Objects.toString(type, "")), "检验事件类型无效");
        installed();
        parent(type, taskId, false);
        return chain(type, taskId);
    }

    private List<QmsInspectionEventEntity> chain(String type, Long taskId) {
        var footprint =
                events.footprint(
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode(),
                        type,
                        taskId);
        require(
                footprint != null
                        && footprint.get("event_count") instanceof Number
                        && footprint.get("payload_bytes") instanceof Number,
                "检验事件范围或容量不可核验");
        long count = ((Number) footprint.get("event_count")).longValue();
        long bytes = ((Number) footprint.get("payload_bytes")).longValue();
        require(count >= 0 && count <= 5000, "单任务质检历史超过5000条，请先治理，禁止截断追溯依据");
        require(bytes >= 0 && bytes <= MAX_CHAIN_BYTES, "单任务检验事件原文累计超过8MiB，请先治理");
        var rows =
                events.selectList(
                        scope(type, taskId)
                                .orderByAsc(QmsInspectionEventEntity::getSequenceNo)
                                .last("LIMIT 5001"));
        QmsInspectionHistorySupport.bounded(rows.size());
        QmsInspectionEventEntity previous = null;
        for (var row : rows) {
            validate(row, previous, true);
            previous = row;
        }
        require(
                rows.size() == count
                        && rows.stream()
                                        .mapToLong(
                                                row ->
                                                        row.getPayload()
                                                                .getBytes(StandardCharsets.UTF_8)
                                                                .length)
                                        .sum()
                                == bytes,
                "检验事件完整范围已变化，请重新读取");
        return rows;
    }

    private void validateSource(JsonNode history, JsonNode parent, String action) {
        boolean sameQuantity = Objects.equals(history.get("num"), parent.get("num"));
        boolean positive =
                history.path("num").isIntegralNumber() && history.path("num").longValue() > 0;
        String result = parent.path("result").asText();
        switch (action) {
            case "RESULT" ->
                    require(
                            sameQuantity
                                    && positive
                                    && Set.of("QUALIFIED", "UN_QUALIFIED").contains(result)
                                    && history.path("qualifiedStatus").isBoolean()
                                    && history.path("qualifiedStatus").booleanValue()
                                            == "QUALIFIED".equals(result),
                            "原检验历史与锁定父任务判定或数量不一致，禁止拼接原件");
            case "APPEND" ->
                    require(
                            positive
                                    && parent.path("num").isIntegralNumber()
                                    && history.path("num").longValue()
                                            <= parent.path("num").longValue(),
                            "追加历史与锁定父任务数量不一致");
            case "CANCELED" -> require(sameQuantity && "CANCELED".equals(result), "作废历史与锁定父任务不一致");
            default ->
                    require(
                            sameQuantity
                                    && positive
                                    && (!"EXEMPTION".equals(action) || "EXEMPTION".equals(result)),
                            "检验历史与锁定父任务数量或动作不一致");
        }
    }

    private void installed() {
        Integer version;
        try {
            version = events.schemaVersion();
        } catch (RuntimeException failure) {
            throw new CheckedException("请先安装并核验生产检验事件结构353", failure);
        }
        require(Objects.equals(1, version), "生产检验事件结构或永久守卫未就绪，禁止降级漏记");
    }

    private Map<String, Object> parent(String type, Long id, boolean lock) {
        require(id != null && id > 0, "检验事件缺少实际父任务");
        Map<String, Object> result =
                "PROCEDURE".equals(type)
                        ? events.procedureSnapshot(
                                TenantContextHolder.getTenantId(),
                                FactoryContextHolder.getFactoryCode(),
                                id,
                                lock)
                        : events.productSnapshot(
                                TenantContextHolder.getTenantId(),
                                FactoryContextHolder.getFactoryCode(),
                                id,
                                lock);
        require(result != null, "当前组织不存在检验事件的实际父任务");
        return result;
    }

    private LambdaQueryWrapper<QmsInspectionEventEntity> scope(String type, Long taskId) {
        return new LambdaQueryWrapper<QmsInspectionEventEntity>()
                .eq(QmsInspectionEventEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(QmsInspectionEventEntity::getFactoryCode, FactoryContextHolder.getFactoryCode())
                .eq(QmsInspectionEventEntity::getInspectionType, type)
                .eq(QmsInspectionEventEntity::getTaskId, taskId);
    }

    private String payload(
            QmsInspectionEventEntity event,
            JsonNode history,
            Map<String, Object> task,
            String dispositionPayload) {
        var source = JSON.valueToTree(task);
        var frozen = new TreeMap<String, Object>();
        for (String name :
                List.of(
                        "sourceTaskCode",
                        "sourceRequestId",
                        "sourceRequestFingerprint",
                        "matCode",
                        "processCode",
                        "labelType",
                        "standardId",
                        "batchNo",
                        "num",
                        "inspectionNum",
                        "status",
                        "result",
                        "usableNum",
                        "handleNum",
                        "handleResult",
                        "orderNo",
                        "orderId",
                        "matBarcode",
                        "deptCode",
                        "latestInspectionTime",
                        "latestAppendTime")) {
            JsonNode value = source.get(name);
            frozen.put(
                    name,
                    value == null || value.isNull()
                            ? null
                            : "standardId".equals(name) ? value.asText() : value);
        }
        var body = new TreeMap<String, Object>();
        body.put("schemaVersion", dispositionPayload == null ? 1 : 2);
        body.put("sourceSystem", "QMS");
        body.put("eventId", event.getId().toString());
        body.put("tenantId", event.getTenantId().toString());
        body.put("factoryCode", event.getFactoryCode());
        body.put("inspectionType", event.getInspectionType());
        body.put("taskId", event.getTaskId().toString());
        body.put("historyId", event.getHistoryId().toString());
        body.put("eventType", event.getEventType());
        body.put("sequenceNo", event.getSequenceNo());
        body.put(
                "previousEventId",
                event.getPreviousEventId() == null ? null : event.getPreviousEventId().toString());
        body.put("previousFingerprint", event.getPreviousFingerprint());
        body.put("occurredAt", event.getOccurredAt().toInstant().toString());
        body.put("quantity", history.get("num"));
        body.put("qualified", history.get("qualifiedStatus"));
        body.put("cancelReason", history.get("cancelReason"));
        body.put("recordedBy", history.get("createBy"));
        body.put("source", frozen);
        body.put("firstPassEligible", false);
        body.put("dataGaps", List.of("未冻结检验计量单位及稳定MES节点", "原始首检/复检关系与按件合格量尚未认证"));
        if (dispositionPayload != null) {
            body.put("dispositionPayload", dispositionPayload);
            body.put(
                    "dispositionFingerprint",
                    com.kitex.mom.qms.dto.inspection.QmsProcedureDispositionContract.hash(
                            dispositionPayload));
            com.kitex.mom.qms.dto.inspection.QmsProcedureDispositionContract.validateEvent(
                    JSON.valueToTree(body));
        }
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception failure) {
            throw new CheckedException("检验事件原文生成失败", failure);
        }
    }

    private void validate(
            QmsInspectionEventEntity row, QmsInspectionEventEntity previous, boolean chain) {
        require(
                row.getId() != null
                        && row.getId() > 0
                        && row.getHistoryId() != null
                        && row.getHistoryId() > 0
                        && row.getSequenceNo() != null
                        && row.getSequenceNo() > 0
                        && row.getSequenceNo() <= 5000
                        && row.getOccurredAt() != null
                        && ACTIONS.contains(Objects.toString(row.getEventType(), ""))
                        && Objects.equals(row.getTenantId(), TenantContextHolder.getTenantId())
                        && Objects.equals(
                                row.getFactoryCode(), FactoryContextHolder.getFactoryCode())
                        && row.getPayload() != null
                        && row.getPayload().getBytes(StandardCharsets.UTF_8).length <= 65536
                        && Objects.equals(row.getFingerprint(), hash(row.getPayload())),
                "检验事件原件或摘要损坏");
        try {
            JsonNode raw = JSON.readTree(row.getPayload());
            require(
                    raw.path("schemaVersion").isInt()
                            && raw.path("schemaVersion").intValue()
                                    == ("DISPOSITION".equals(row.getEventType()) ? 2 : 1)
                            && "QMS".equals(raw.path("sourceSystem").asText())
                            && row.getId().toString().equals(raw.path("eventId").asText())
                            && row.getTenantId().toString().equals(raw.path("tenantId").asText())
                            && row.getFactoryCode().equals(raw.path("factoryCode").asText())
                            && row.getTaskId().toString().equals(raw.path("taskId").asText())
                            && row.getHistoryId().toString().equals(raw.path("historyId").asText())
                            && row.getInspectionType().equals(raw.path("inspectionType").asText())
                            && row.getEventType().equals(raw.path("eventType").asText())
                            && raw.path("sequenceNo").isIntegralNumber()
                            && raw.path("sequenceNo").longValue() == row.getSequenceNo()
                            && row.getOccurredAt()
                                    .toInstant()
                                    .toString()
                                    .equals(raw.path("occurredAt").asText())
                            && Objects.equals(
                                    row.getPreviousEventId() == null
                                            ? null
                                            : row.getPreviousEventId().toString(),
                                    text(raw.get("previousEventId")))
                            && Objects.equals(
                                    row.getPreviousFingerprint(),
                                    text(raw.get("previousFingerprint")))
                            && raw.path("firstPassEligible").isBoolean()
                            && !raw.path("firstPassEligible").booleanValue(),
                    "检验事件原文与索引身份不一致");
            if ("DISPOSITION".equals(row.getEventType())) {
                com.kitex.mom.qms.dto.inspection.QmsProcedureDispositionContract.validateEvent(raw);
                if (chain) {
                    require(previous != null, "处置缺少前件");
                    com.kitex.mom.qms.dto.inspection.QmsProcedureDispositionContract
                            .verifyPredecessor(raw, previous.getPayload());
                }
            }
        } catch (CheckedException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CheckedException("检验事件原文不可解析", failure);
        }
        if (chain)
            require(
                    row.getSequenceNo() == (previous == null ? 1 : previous.getSequenceNo() + 1)
                            && Objects.equals(
                                    row.getPreviousEventId(),
                                    previous == null ? null : previous.getId())
                            && Objects.equals(
                                    row.getPreviousFingerprint(),
                                    previous == null ? null : previous.getFingerprint()),
                    "检验事件链缺失或前版引用不一致，拒绝截断追溯");
    }

    private String text(JsonNode value) {
        return value == null || value.isNull() ? null : value.asText();
    }

    private String hash(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JVM缺少SHA-256", failure);
        }
    }
}
