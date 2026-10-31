package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesInspectionEventDeliveryDTO;
import com.kitex.mom.mes.dto.MesInspectionEventReceiptDTO;
import com.kitex.mom.mes.entity.MesInspectionEventInboxEntity;
import com.kitex.mom.mes.mapper.MesInspectionEventInboxMapper;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * 只持久化QMS不可变事件；接收成功不改变质量门、数量或库存。
 */
@Service
@RequiredArgsConstructor
public class MesInspectionEventInboxService {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> ACTIONS =
            Set.of("CREATE", "APPEND", "RESULT", "CANCELED", "EXEMPTION", "DISPOSITION");
    private static final Set<String> SOURCE_FIELDS =
            Set.of(
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
                    "latestAppendTime");
    private final MesInspectionEventInboxMapper inbox;
    private final MesQualityGateSourceEvidence sources;
    private final com.kitex.mom.mes.mapper.MesInspectionEventStateMapper states;

    @Value(
            "${mom.qms-inspection-event.delivery-secret:${MOM_QMS_INSPECTION_EVENT_DELIVERY_SECRET:}}")
    private String deliverySecret;

    private static void link(MesInspectionEventInboxEntity e, MesInspectionEventInboxEntity head) {
        require(
                e.getSequenceNo() == (head == null ? 1L : head.getSequenceNo() + 1)
                        && Objects.equals(
                        e.getPreviousEventId(), head == null ? null : head.getId())
                        && Objects.equals(
                        e.getPreviousFingerprint(),
                        head == null ? null : head.getFingerprint()),
                "检验事件未连续或前件引用不一致，请从缺失原件恢复");
    }

    private static boolean sameIndexed(
            MesInspectionEventInboxEntity a, MesInspectionEventInboxEntity b) {
        return Objects.equals(a.getId(), b.getId())
                && Objects.equals(a.getTenantId(), b.getTenantId())
                && Objects.equals(a.getFactoryCode(), b.getFactoryCode())
                && Objects.equals(a.getInspectionType(), b.getInspectionType())
                && Objects.equals(a.getTaskId(), b.getTaskId())
                && Objects.equals(a.getHistoryId(), b.getHistoryId())
                && Objects.equals(a.getEventType(), b.getEventType())
                && Objects.equals(a.getSequenceNo(), b.getSequenceNo())
                && Objects.equals(a.getPreviousEventId(), b.getPreviousEventId())
                && Objects.equals(a.getPreviousFingerprint(), b.getPreviousFingerprint())
                && Objects.equals(a.getOccurredAt(), b.getOccurredAt());
    }

    private static MesInspectionEventReceiptDTO receipt(MesInspectionEventInboxEntity e) {
        return new MesInspectionEventReceiptDTO()
                .setEventId(e.getId())
                .setTaskId(e.getTaskId())
                .setSequenceNo(e.getSequenceNo())
                .setFingerprint(e.getFingerprint())
                .setReceivedAt(new Date(e.getReceivedAt().getTime()))
                .setStatus("RECEIVED")
                .setApplied(false);
    }

    private static String text(JsonNode j, String key, boolean nullable) {
        var v = j.get(key);
        require(
                v != null && (v.isTextual() || (nullable && v.isNull())),
                "检验事件文本字段缺失或类型不正确：" + key);
        return v.isNull() ? null : v.textValue();
    }

    private static Long id(JsonNode j, String key, boolean nullable) {
        String s = text(j, key, nullable);
        if (s == null) return null;
        require(s.matches("[1-9][0-9]{0,18}"), "检验事件ID格式无效：" + key);
        long id = Long.parseLong(s);
        require(id > 0, "检验事件ID必须为正数");
        return id;
    }

    private static Long integer(JsonNode j, String key, boolean nullable) {
        var v = j.get(key);
        require(
                v != null
                        && ((nullable && v.isNull())
                        || (v.isIntegralNumber() && v.canConvertToLong())),
                "检验事件整数字段缺失或类型不正确：" + key);
        return v.isNull() ? null : v.longValue();
    }

    private static Integer quantity(JsonNode j, String key, boolean nullable) {
        Long n = integer(j, key, nullable);
        require(n == null || (n >= 0 && n <= Integer.MAX_VALUE), "检验事件数量超出范围：" + key);
        return n == null ? null : n.intValue();
    }

    private static long number(Map<String, Object> values, String key) {
        Object v = values == null ? null : values.get(key);
        require(v instanceof Number && ((Number) v).longValue() >= 0, "检验收件容量不可核验");
        return ((Number) v).longValue();
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new CheckedException(reason);
    }

    private static void deny(boolean condition, String reason) {
        if (!condition) throw new AccessDeniedException(reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public MesInspectionEventReceiptDTO accept(MesInspectionEventDeliveryDTO d) {
        authenticate(d);
        return store(d);
    }

    /**
     * 已验签正式修订事务内复用标准解码/收件；不提供HTTP跳过门禁参数。
     */
    MesInspectionEventReceiptDTO acceptRevision(String raw, String fingerprint) {
        require(
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive(),
                "正式修订收件必须加入实际事务");
        MesQualityGateSourceEvidence.scope();
        var event = decode(raw, fingerprint);
        return store(
                new MesInspectionEventDeliveryDTO()
                        .setEventId(event.getId())
                        .setTenantId(TenantContextHolder.getTenantId())
                        .setFactoryCode(FactoryContextHolder.getFactoryCode())
                        .setPayload(raw)
                        .setFingerprint(fingerprint));
    }

    private MesInspectionEventReceiptDTO store(MesInspectionEventDeliveryDTO d) {
        require(Objects.equals(1, states.schemaVersion()), "普通检验事件应用结构未就绪，禁止绕过接管");
        require(Objects.equals(1, inbox.schemaVersion()), "普通检验事件收件结构或守卫未就绪");
        var event = decode(d.getPayload(), d.getFingerprint());
        require(Objects.equals(event.getId(), d.getEventId()), "原文事件ID与签名身份不一致");
        var bindings = inbox.bindings(d.getTenantId(), d.getFactoryCode(), event.getTaskId());
        require(bindings.size() == 1, "当前组织缺少唯一的检验生成记录，暂不能收件");
        var binding = bindings.get(0);
        require("1".equals(binding.getGenFlag()), "检验创建回执尚未确认，暂不能收件");
        event.setGenRecordId(binding.getId());
        var footprint = inbox.footprint(d.getTenantId(), d.getFactoryCode(), event.getTaskId());
        long count = number(footprint, "event_count"), bytes = number(footprint, "payload_bytes");
        require(count <= 5000 && bytes <= 8388608L, "既有检验收件超过容量上限，请先治理");
        var chain = inbox.chain(d.getTenantId(), d.getFactoryCode(), event.getTaskId());
        require(chain.size() == count, "检验收件容量与实际链不一致");
        MesInspectionEventInboxEntity head = null, existing = null;
        long actualBytes = 0;
        var histories = new HashSet<Long>();
        for (var stored : chain) {
            require(stored.getPayload() != null, "既有收件原文缺失");
            var original = decode(stored.getPayload(), stored.getFingerprint());
            require(
                    sameIndexed(stored, original)
                            && Objects.equals(stored.getGenRecordId(), binding.getId())
                            && stored.getReceivedAt() != null,
                    "既有收件索引或绑定与原文不一致");
            link(stored, head);
            require(histories.add(stored.getHistoryId()), "既有收件历史身份重复");
            actualBytes =
                    Math.addExact(
                            actualBytes,
                            stored.getPayload().getBytes(StandardCharsets.UTF_8).length);
            if (Objects.equals(stored.getId(), event.getId())) existing = stored;
            head = stored;
        }
        require(actualBytes == bytes, "既有收件原文容量不一致");
        if (existing != null) {
            require(
                    sameIndexed(existing, event)
                            && existing.getPayload().equals(event.getPayload())
                            && existing.getFingerprint().equals(event.getFingerprint()),
                    "同一检验事件已收不同原件，拒绝换件");
            ensureState(binding, existing, true);
            return receipt(existing);
        }
        link(event, head);
        require(!histories.contains(event.getHistoryId()), "同一检验历史不能更换事件身份");
        require(
                count < 5000
                        && bytes + event.getPayload().getBytes(StandardCharsets.UTF_8).length
                        <= 8388608L,
                "单任务收件超过5000条或8MiB，请先治理");
        sources.verifyOriginal(binding, source(event));
        event.setReceivedAt(new Date());
        require(inbox.insert(event) == 1, "检验原文收件未保存，禁止确认成功");
        ensureState(binding, event, count > 0);
        return receipt(event);
    }

    /**
     * 供本域工作器读取完整本地原件；不做网络调用，不暴露HTTP写口。
     */
    List<MesInspectionEventInboxEntity> localChain(
            com.kitex.mom.mes.entity.MesInspectionGenRecordEntity binding) {
        MesQualityGateSourceEvidence.scope();
        require(Objects.equals(1, schema()), "检验收件守卫未就绪");
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        var footprint = inbox.footprint(tenant, factory, binding.getInspectionId());
        long count = number(footprint, "event_count"), bytes = number(footprint, "payload_bytes");
        require(count <= 5000 && bytes <= 8388608L, "检验收件链超过容量，请先治理");
        var rows = inbox.chain(tenant, factory, binding.getInspectionId());
        require(rows.size() == count, "检验收件范围已变化");
        MesInspectionEventInboxEntity head = null;
        long actual = 0;
        var histories = new HashSet<Long>();
        for (var row : rows) {
            var verified = decode(row.getPayload(), row.getFingerprint());
            require(
                    sameIndexed(row, verified)
                            && Objects.equals(row.getGenRecordId(), binding.getId())
                            && row.getReceivedAt() != null
                            && histories.add(row.getHistoryId()),
                    "本地收件索引或绑定损坏");
            link(row, head);
            actual =
                    Math.addExact(actual, row.getPayload().getBytes(StandardCharsets.UTF_8).length);
            head = row;
        }
        require(actual == bytes, "本地收件原文容量不一致");
        return rows;
    }

    MesInspectionEventInboxEntity verified(MesInspectionEventInboxEntity row) {
        require(row != null, "缺少实际收件原文");
        var body = decode(row.getPayload(), row.getFingerprint());
        require(sameIndexed(row, body) && row.getReceivedAt() != null, "收件索引与原文不一致");
        return row;
    }

    private Integer schema() {
        return inbox.schemaVersion();
    }

    private void ensureState(
            com.kitex.mom.mes.entity.MesInspectionGenRecordEntity binding,
            MesInspectionEventInboxEntity event,
            boolean historical) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        var current = states.state(binding.getId(), tenant, factory, false);
        if (Boolean.TRUE.equals(binding.getQualityEventManaged())) {
            require(
                    current != null
                            && Objects.equals(current.getInspectionId(), binding.getInspectionId())
                            && Objects.equals(current.getTaskCode(), binding.getTaskCode())
                            && Objects.equals(
                            current.getProduceTaskCode(), binding.getProduceTaskCode()),
                    "事件接管状态缺失或与绑定不一致");
            return;
        }
        require(current == null, "未接管记录已有未知事件状态");
        require(states.markManaged(binding.getId(), tenant, factory) == 1, "事件接管位保存失败");
        boolean conflict =
                historical
                        || (binding.getInspectionResult() != null
                        && !binding.getInspectionResult().isBlank());
        var state =
                new com.kitex.mom.mes.entity.MesInspectionEventStateEntity()
                        .setId(binding.getId())
                        .setTenantId(tenant)
                        .setFactoryCode(factory)
                        .setInspectionId(binding.getInspectionId())
                        .setTaskCode(binding.getTaskCode())
                        .setProduceTaskCode(binding.getProduceTaskCode())
                        .setLastSequence(0L)
                        .setStatus(conflict ? "CONFLICT" : "ACTIVE")
                        .setVersion(0L)
                        .setCreatedAt(new Date())
                        .setUpdatedAt(new Date())
                        .setBlockedEventId(conflict ? event.getId() : null)
                        .setLastError(conflict ? "旧判定或历史收件须受控接管，不能从当前缓存补造许可" : null);
        require(states.insert(state) == 1, "检验事件初始状态保存失败");
        binding.setQualityEventManaged(true);
    }

    private void authenticate(MesInspectionEventDeliveryDTO d) {
        MesQualityGateSourceEvidence.scope();
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        deny(
                d != null && tenant.equals(d.getTenantId()) && factory.equals(d.getFactoryCode()),
                "普通检验事件投递组织不一致");
        long now = Instant.now().getEpochSecond();
        deny(
                d.getEventId() != null
                        && d.getEventId() > 0
                        && d.getPayload() != null
                        && d.getPayload().length() <= 65536
                        && d.getPayload().getBytes(StandardCharsets.UTF_8).length <= 65536
                        && d.getFingerprint() != null
                        && d.getFingerprint().matches("[0-9a-f]{64}")
                        && d.getIssuedAt() != null
                        && d.getIssuedAt() >= now - 90
                        && d.getIssuedAt() <= now + 15
                        && d.getNonce() != null
                        && d.getNonce().matches("[A-Za-z0-9_-]{16,64}")
                        && d.signatureMatches(deliverySecret),
                "普通检验事件投递签名无效、过期或配置未就绪");
    }

    MesInspectionEventInboxEntity decode(String raw, String fingerprint) {
        require(
                raw != null
                        && raw.length() <= 65536
                        && raw.getBytes(StandardCharsets.UTF_8).length <= 65536
                        && Objects.equals(hash(raw), fingerprint),
                "检验事件原文摘要或长度不一致");
        try {
            JsonNode j = JSON.readTree(raw);
            require(
                    j != null
                            && j.isObject()
                            && j.path("schemaVersion").isInt()
                            && j.get("schemaVersion").intValue()
                            == ("DISPOSITION".equals(j.path("eventType").textValue())
                            ? 2
                            : 1)
                            && "QMS".equals(text(j, "sourceSystem", false))
                            && "PROCEDURE".equals(text(j, "inspectionType", false))
                            && ACTIONS.contains(Objects.toString(text(j, "eventType", false), ""))
                            && j.path("firstPassEligible").isBoolean()
                            && !j.get("firstPassEligible").booleanValue()
                            && j.path("dataGaps").isArray()
                            && j.path("source").isObject(),
                    "普通工序检验事件类型、版本或资格声明无效");
            if ("DISPOSITION".equals(j.path("eventType").textValue())) {
                require(Integer.valueOf(1).equals(inbox.dispositionReady()), "请先安装并核验工序处置结构366");
                com.kitex.mom.qms.dto.inspection.QmsProcedureDispositionContract.validateEvent(j);
            }
            var source = j.get("source");
            var fields = new HashSet<String>();
            source.fieldNames().forEachRemaining(fields::add);
            require(fields.equals(SOURCE_FIELDS), "检验事件缺少完整可空来源字段或含未知来源字段");
            Instant occurred = Instant.parse(text(j, "occurredAt", false));
            require(occurred.getNano() % 1_000_000 == 0, "检验事件时间精度超过现有来源合同");
            var e =
                    new MesInspectionEventInboxEntity()
                            .setId(id(j, "eventId", false))
                            .setTenantId(id(j, "tenantId", false))
                            .setFactoryCode(text(j, "factoryCode", false))
                            .setInspectionType("PROCEDURE")
                            .setTaskId(id(j, "taskId", false))
                            .setHistoryId(id(j, "historyId", false))
                            .setEventType(text(j, "eventType", false))
                            .setSequenceNo(integer(j, "sequenceNo", false))
                            .setPreviousEventId(id(j, "previousEventId", true))
                            .setPreviousFingerprint(text(j, "previousFingerprint", true))
                            .setOccurredAt(Date.from(occurred))
                            .setPayload(raw)
                            .setFingerprint(fingerprint);
            require(
                    Objects.equals(e.getTenantId(), TenantContextHolder.getTenantId())
                            && Objects.equals(
                            e.getFactoryCode(), FactoryContextHolder.getFactoryCode())
                            && e.getSequenceNo() > 0
                            && e.getSequenceNo() <= 5000,
                    "检验事件组织或序号不一致");
            source(e); // 所有可空数量按显式JSON类型解码，不能由实体默认0或整数截断掩盖。
            return e;
        } catch (CheckedException e) {
            throw e;
        } catch (Exception e) {
            throw new CheckedException("检验事件原文不能核验");
        }
    }

    QmsInspectionProcedureTaskEntity source(MesInspectionEventInboxEntity e) {
        try {
            JsonNode s = JSON.readTree(e.getPayload()).get("source");
            var source =
                    new QmsInspectionProcedureTaskEntity()
                            .setSourceTaskCode(text(s, "sourceTaskCode", false))
                            .setSourceRequestId(text(s, "sourceRequestId", false))
                            .setSourceRequestFingerprint(text(s, "sourceRequestFingerprint", false))
                            .setLabelType(text(s, "labelType", false))
                            .setMatCode(text(s, "matCode", false))
                            .setProcessCode(text(s, "processCode", false))
                            .setStandardId(id(s, "standardId", true))
                            .setBatchNo(text(s, "batchNo", true))
                            .setOrderNo(text(s, "orderNo", false))
                            .setOrderId(text(s, "orderId", true))
                            .setMatBarcode(text(s, "matBarcode", true))
                            .setDeptCode(text(s, "deptCode", true))
                            .setNum(quantity(s, "num", false))
                            .setInspectionNum(quantity(s, "inspectionNum", true))
                            .setUsableNum(quantity(s, "usableNum", true))
                            .setHandleNum(quantity(s, "handleNum", true))
                            .setHandleResult(text(s, "handleResult", true))
                            .setStatus(text(s, "status", false))
                            .setResult(text(s, "result", true));
            require(source.getNum() > 0, "检验批数量必须为正数");
            for (String name : List.of("latestInspectionTime", "latestAppendTime")) {
                var value = s.get(name);
                require(
                        value != null
                                && (value.isNull()
                                || value.isTextual()
                                || value.isIntegralNumber()),
                        "检验来源时间类型不正确");
            }
            source.setId(e.getTaskId());
            source.setTenantId(e.getTenantId());
            source.setFactoryCode(e.getFactoryCode());
            return source;
        } catch (CheckedException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CheckedException("检验冻结来源不可核验");
        }
    }
}
