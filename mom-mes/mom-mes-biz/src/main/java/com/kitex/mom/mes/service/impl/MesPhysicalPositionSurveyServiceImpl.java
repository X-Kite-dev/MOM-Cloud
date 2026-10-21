package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesPhysicalPositionSurveyDTO;
import com.kitex.mom.mes.dto.MesPhysicalTransformSourceBundleDTO;
import com.kitex.mom.mes.entity.MesPhysicalPositionAllocationEntity;
import com.kitex.mom.mes.entity.MesPhysicalPositionOperationEntity;
import com.kitex.mom.mes.entity.MesPhysicalPositionSlotEntity;
import com.kitex.mom.mes.entity.MesPhysicalPositionSourceEntity;
import com.kitex.mom.mes.entity.MesPhysicalPositionSurveyEntity;
import com.kitex.mom.mes.mapper.MesPhysicalPositionAllocationMapper;
import com.kitex.mom.mes.mapper.MesPhysicalPositionOperationMapper;
import com.kitex.mom.mes.mapper.MesPhysicalPositionSlotMapper;
import com.kitex.mom.mes.mapper.MesPhysicalPositionSourceMapper;
import com.kitex.mom.mes.mapper.MesPhysicalPositionSurveyMapper;
import com.kitex.mom.mes.service.MesPhysicalPositionSurveyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 原号下的永久位置观察、异人确认/驳回和本人取消。
 * 一个母件完整布局及每条精确库存永久保存，未库存化/缺位/未知不冒充合格实物。
 * 不改变原报告、库存、PASS或转换位置余额；CONFIRMED不是PREPARED/APPLIED。
 */
@Service
@RequiredArgsConstructor
public class MesPhysicalPositionSurveyServiceImpl
        extends ServiceImpl<MesPhysicalPositionSurveyMapper, MesPhysicalPositionSurveyEntity>
        implements MesPhysicalPositionSurveyService {
    /** 独立永久原号域，采集号和操作号共用，跨工厂不重复占用同租户号。 */
    private static final String REQUEST_LOCK_VERSION = "MES_PHYSICAL_POSITION_REQUEST_V1";
    /** 只用于比较已逐原版本验证的领域节点，不称为旧SOURCE_V2的SHA。 */
    private static final String DOMAIN_COMPARISON_VERSION = "MES_PHYSICAL_POSITION_SOURCE_DOMAIN_V1";
    private final MesPhysicalPositionSourceMapper sourceRows;
    private final MesPhysicalPositionSlotMapper slotRows;
    private final MesPhysicalPositionAllocationMapper allocationRows;
    private final MesPhysicalPositionOperationMapper operations;
    private final MesPhysicalPositionSurveyCodec surveyCodec;
    private final MesPhysicalPositionSurveyPrecheckService precheck;
    private final MesPhysicalTransformCodec codec;
    private final MesPhysicalTransformSourceFreezeService sources;
    private final MesPhysicalTransformSourceScopeLock scope;
    private final MesActualFactoryAccessService factoryAccess;
    private final PlatformTransactionManager transactionManager;

    @Override
    @PreAuthorize("@pms.hasPermission('mes_physicalTransform_operate')")
    public MesPhysicalPositionSurveyDTO.Receipt capture(String requestId, String rawJson) {
        codec.code(requestId, "永久位置采集号", 100);
        var body = surveyCodec.body(rawJson);
        Identity owner = identity();
        assertExpected(body.getExpectedTenantId(), body.getExpectedFactoryCode(), body.getExpectedActorUserId(), owner);
        String fingerprint = codec.fingerprint(MesPhysicalPositionSurveyCodec.VERSION, rawJson);
        return independent(() -> {
            requireAvailable();
            requestLock(owner, requestId);
            var prior = baseMapper.permanent(owner.tenantId(), requestId);
            if (prior != null) {
                assertScope(prior, owner);
                require(owner.actorId().equals(prior.getActorId()) && owner.actorCode().equals(prior.getActorCode())
                        && fingerprint.equals(prior.getSurveyFingerprint()) && rawJson.equals(prior.getSurveySnapshot()),
                        "原采集号属于另一本人或正文，不能覆盖或另号猜重投");
                return receiptOf(prior, owner);
            }
            require(operations.permanent(owner.tenantId(), requestId) == null, "该永久原号已用于另一操作，不重用原号");
            requireReady();
            scope.acquire(owner.tenantId(), owner.factoryCode());
            requireReady();
            var bundle = sources.lockAndRead(body.getSources().stream().map(MesPhysicalPositionSurveyDTO.Source::getStockId).toList());
            var preview = precheck.validateLocked(rawJson, body, bundle);
            LocalDateTime time = LocalDateTime.now();
            Long surveyId = IdWorker.getId();
            Long operationId = IdWorker.getId();
            var row = new MesPhysicalPositionSurveyEntity().setFactoryCode(owner.factoryCode()).setRequestId(requestId)
                    .setSurveyVersion(MesPhysicalPositionSurveyCodec.VERSION).setSurveySnapshot(rawJson).setSurveyFingerprint(fingerprint)
                    .setEvidenceVersion(MesPhysicalPositionSurveyCodec.EVIDENCE_VERSION).setEvidenceSnapshot(preview.getEvidenceSnapshot())
                    .setEvidenceFingerprint(preview.getEvidenceFingerprint()).setCanonicalIdentifier(preview.getCanonicalIdentifier())
                    .setPhysicalObjectCount(1L).setMaterialCode(bundle.getSources().get(0).getSource().getMaterialCode())
                    .setLayoutCode(preview.getLayoutCode()).setStandardId(codec.id(preview.getStandardId()))
                    .setStandardFingerprint(preview.getStandardFingerprint()).setActorId(owner.actorId()).setActorCode(owner.actorCode())
                    .setStatus("OBSERVED").setRevision(1L).setLastOperationId(operationId).setObservedTime(time);
            initialize(row, owner, surveyId);
            require(baseMapper.insert(row) == 1, "位置原账必要写未恰好一行，整次采集回滚");
            var sourceRecords = new ArrayList<Map<String, Object>>(bundle.getSources().size());
            for (var entry : bundle.getSources()) {
                var fact = entry.getSource();
                var source = new MesPhysicalPositionSourceEntity().setFactoryCode(owner.factoryCode()).setSurveyId(surveyId)
                        .setStockId(codec.id(entry.getStockId())).setSourceReportId(codec.id(fact.getSourceReportId()))
                        .setSourceVersion(entry.getSourceVersion()).setSourceSnapshot(entry.getSourceSnapshot())
                        .setSourceFingerprint(entry.getSourceFingerprint()).setNativeQuantity(codec.quantity(fact.getQuantity(), false, "原库存量"))
                        .setNativeUnit(fact.getUnit()).setEquivalentQuantity(codec.quantity(preview.getSourceEquivalents().get(entry.getStockId()), false, "原当量"));
                initialize(source, owner, IdWorker.getId());
                var record = batchRecord(source, surveyId, owner, time);
                record.put("stock_id", source.getStockId().toString());
                record.put("source_report_id", source.getSourceReportId().toString());
                record.put("source_version", source.getSourceVersion());
                record.put("source_snapshot", source.getSourceSnapshot());
                record.put("source_fingerprint", source.getSourceFingerprint());
                record.put("native_quantity", source.getNativeQuantity().toPlainString());
                record.put("native_unit", source.getNativeUnit());
                record.put("equivalent_quantity", source.getEquivalentQuantity().toPlainString());
                sourceRecords.add(record);
            }
            var positionRecords = new ArrayList<Map<String, Object>>(body.getPositions().size());
            var allocationRecords = new ArrayList<Map<String, Object>>();
            for (var position : body.getPositions()) {
                var slot = new MesPhysicalPositionSlotEntity().setFactoryCode(owner.factoryCode()).setSurveyId(surveyId)
                        .setPositionCode(position.getPositionCode()).setPresence(position.getPresence())
                        .setPhysicalCount("UNKNOWN".equals(position.getPresence()) ? null : "MISSING".equals(position.getPresence()) ? 0L : 1L);
                initialize(slot, owner, IdWorker.getId());
                var record = batchRecord(slot, surveyId, owner, time);
                record.put("position_code", slot.getPositionCode());
                record.put("presence", slot.getPresence());
                record.put("physical_count", slot.getPhysicalCount() == null ? null : slot.getPhysicalCount().toString());
                positionRecords.add(record);
                for (var allocation : position.getAllocations()) {
                    var edge = new MesPhysicalPositionAllocationEntity().setFactoryCode(owner.factoryCode()).setSurveyId(surveyId)
                            .setPositionCode(position.getPositionCode()).setStockId(codec.id(allocation.getStockId()))
                            .setEquivalentQuantity(codec.quantity(allocation.getQuantity(), false, "逐位来源当量"));
                    initialize(edge, owner, IdWorker.getId());
                    var edgeRecord = batchRecord(edge, surveyId, owner, time);
                    edgeRecord.put("position_code", edge.getPositionCode());
                    edgeRecord.put("stock_id", edge.getStockId().toString());
                    edgeRecord.put("equivalent_quantity", edge.getEquivalentQuantity().toPlainString());
                    allocationRecords.add(edgeRecord);
                }
            }
            // 三表各一条SQL：同步核全部必要写数，FK/即时membership/最终双侧证明仍在本事务。
            String tenantId = owner.tenantId().toString();
            String surveyKey = surveyId.toString();
            require(sourceRows.insertFrozenRows(codec.snapshot(sourceRecords), tenantId, owner.factoryCode(), surveyKey)
                    == sourceRecords.size(), "精确来源批量必要写数不同，整次采集回滚");
            require(slotRows.insertFrozenRows(codec.snapshot(positionRecords), tenantId, owner.factoryCode(), surveyKey)
                    == positionRecords.size(), "完整位置批量必要写数不同，整次采集回滚");
            if (!allocationRecords.isEmpty()) {
                require(allocationRows.insertFrozenRows(codec.snapshot(allocationRecords), tenantId, owner.factoryCode(), surveyKey)
                        == allocationRecords.size(), "全部位置来源边批量必要写数不同，整次采集回滚");
            }
            var event = event(owner, surveyId, requestId, "OBSERVE", 1L, rawJson, MesPhysicalPositionSurveyCodec.VERSION, time);
            event.setId(operationId);
            require(operations.insert(event) == 1, "原采集事件未恰好一行，整次采集回滚");
            return receiptOf(baseMapper.permanent(owner.tenantId(), requestId), owner);
        });
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mes_physicalTransform_operate')")
    public MesPhysicalPositionSurveyDTO.Receipt decide(String requestId, String operationRequestId, String rawJson) {
        codec.code(requestId, "永久原采集号", 100);
        codec.code(operationRequestId, "永久原决策号", 100);
        require(!requestId.equals(operationRequestId), "决策号不能复用原采集号");
        var input = surveyCodec.decision(rawJson);
        Identity owner = identity();
        assertExpected(input.getExpectedTenantId(), input.getExpectedFactoryCode(), input.getExpectedActorUserId(), owner);
        String fingerprint = codec.fingerprint(MesPhysicalPositionSurveyCodec.DECISION_VERSION, rawJson);
        return independent(() -> {
            requireAvailable();
            // 多原号按同一稳定序try锁，失败保留原号，不持部分锁等待另一端。
            for (String key : List.of(requestId, operationRequestId).stream().sorted().toList()) requestLock(owner, key);
            var row = baseMapper.permanent(owner.tenantId(), requestId);
            assertScope(row, owner);
            var original = receiptOf(row, owner);
            require(input.getSurveyFingerprint().equals(row.getSurveyFingerprint())
                    && input.getEvidenceFingerprint().equals(row.getEvidenceFingerprint()), "原采集全文或服务器证据SHA不同");
            var previous = operations.permanent(owner.tenantId(), operationRequestId);
            if (previous != null) {
                require(row.getId().equals(previous.getSurveyId()) && owner.factoryCode().equals(previous.getFactoryCode())
                        && owner.actorId().equals(previous.getActorId()) && owner.actorCode().equals(previous.getActorCode())
                        && rawJson.equals(previous.getRequestSnapshot()) && fingerprint.equals(previous.getRequestFingerprint()),
                        "原决策号已用于另一操作/本人/正文，不覆盖原事件");
                return original;
            }
            require(baseMapper.permanent(owner.tenantId(), operationRequestId) == null, "决策号已用于另一采集原账");
            require("OBSERVED".equals(row.getStatus()) && Long.valueOf(1).equals(row.getRevision()), "原观察已终结，不重开或根据迟到响应覆盖");
            boolean cancel = "CANCEL".equals(input.getAction());
            require(cancel ? owner.actorId().equals(row.getActorId()) && owner.actorCode().equals(row.getActorCode())
                    : !owner.actorId().equals(row.getActorId()) && !owner.actorCode().equals(row.getActorCode()),
                    cancel ? "只能由原采集本人取消" : "确认/驳回必须由不同用户及账号进行，不能本人会签");
            requireReady();
            scope.acquire(owner.tenantId(), owner.factoryCode());
            requireReady();
            var locked = baseMapper.locked(owner.tenantId(), requestId);
            require(locked != null && row.getId().equals(locked.getId()) && "OBSERVED".equals(locked.getStatus()), "原观察取锁后已改变");
            var event = event(owner, row.getId(), operationRequestId, input.getAction(), 2L, rawJson,
                    MesPhysicalPositionSurveyCodec.DECISION_VERSION, LocalDateTime.now());
            if ("CONFIRM".equals(input.getAction())) {
                var observed = surveyCodec.body(row.getSurveySnapshot());
                require(observed.getPositions().stream().noneMatch(slot -> "UNKNOWN".equals(slot.getPresence())), "实际位置仍有UNKNOWN，不能异人确认");
                var current = sources.lockAndRead(observed.getSources().stream().map(MesPhysicalPositionSurveyDTO.Source::getStockId).toList());
                verifyUnchangedSources(row, current);
                event.setVerificationSnapshot(current.getBundleSnapshot()).setVerificationFingerprint(current.getBundleFingerprint());
            }
            require(operations.insert(event) == 1, "永久决策事件未恰好一行，整次决策回滚");
            String status = switch (input.getAction()) {
                case "CONFIRM" -> "CONFIRMED";
                case "REJECT" -> "REJECTED";
                default -> "CANCELLED";
            };
            require(baseMapper.decide(row.getId(), owner.tenantId(), owner.factoryCode(), input.getSurveyFingerprint(),
                    input.getEvidenceFingerprint(), status, event.getId(), event.getOccurredTime()) == 1, "原观察决策CAS未恰好一行");
            var receipt = receiptOf(baseMapper.permanent(owner.tenantId(), requestId), owner);
            require(status.equals(receipt.getStatus()), "决策后原账状态回读不一致，整次回滚");
            return receipt;
        });
    }

    @Override
    @PreAuthorize("@pms.hasPermission('mes_physicalTransform_operate')")
    public MesPhysicalPositionSurveyDTO.Receipt receipt(String requestId) {
        codec.code(requestId, "永久原采集号", 100);
        Identity owner = identity();
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setReadOnly(true);
        return tx.execute(status -> {
            if (!Boolean.TRUE.equals(baseMapper.available())) return absent(requestId, owner, "UNAVAILABLE");
            var row = baseMapper.permanent(owner.tenantId(), requestId);
            return row == null ? absent(requestId, owner, "ABSENT") : receiptOf(row, owner);
        });
    }

    /** 异人比较所有原领域事实，仅顶层当次观察人不同；不重算/改写旧SOURCE的raw或SHA。 */
    private void verifyUnchangedSources(MesPhysicalPositionSurveyEntity row, MesPhysicalTransformSourceBundleDTO current) {
        require(current.getTenantId().equals(row.getTenantId().toString()) && current.getFactoryCode().equals(row.getFactoryCode()), "确认来源实际组织已变");
        var originalBundle = originalBundle(row);
        require(MesPhysicalTransformSourceFreezeService.BUNDLE_VERSION.equals(current.getVersion())
                && current.getBundleFingerprint().equals(codec.fingerprint(current.getVersion(), current.getBundleSnapshot())), "异人重读完整包版本/SHA损坏");
        var currentDomain = new TreeMap<String, JsonNode>();
        for (var entry : current.getSources()) {
            require(currentDomain.put(entry.getStockId(), domain(entry.getSourceSnapshot(), entry.getSourceFingerprint())) == null, "确认来源ID重复");
        }
        var originalDomain = new TreeMap<String, JsonNode>();
        for (var entry : originalBundle.path("sources")) {
            require(originalDomain.put(entry.path("stockId").textValue(), domain(entry.path("sourceSnapshot").textValue(),
                    entry.path("sourceFingerprint").textValue())) == null, "原来源ID重复");
        }
        require(originalDomain.equals(currentDomain), "原库存/报告/工程/任务图/全量同码库存或阻断事实已漂移，请驳回原观察后按真实新事实重新采集，不能覆盖原文");
    }

    /** 独立比较域只排除V2两顶层观察人字段；不递归删除历史审计，不遗漏原V1/SHA、数组和完整图。 */
    private JsonNode domain(String raw, String sha) {
        var value = codec.objectDocument(raw, MesPhysicalPositionSurveyCodec.MAX_DOCUMENT);
        require(MesPhysicalTransformSourceFreezeService.SOURCE_VERSION.equals(value.path("version").textValue())
                && Objects.equals(sha, codec.fingerprint(MesPhysicalTransformSourceFreezeService.SOURCE_VERSION, raw)), "原完整V2来源版本/SHA不一致");
        var copy = ((ObjectNode) value).deepCopy();
        copy.remove(List.of("actorUserId", "actorCode"));
        return codec.objectDocument(codec.snapshot(new TreeMap<>(java.util.Map.of("version", DOMAIN_COMPARISON_VERSION, "domain", copy))),
                MesPhysicalPositionSurveyCodec.MAX_EVIDENCE);
    }

    /** 一条SQL同提交边界读主及全部子账；回执不读取当前库存，不推断未知提交结果。 */
    private MesPhysicalPositionSurveyDTO.Receipt receiptOf(MesPhysicalPositionSurveyEntity row, Identity owner) {
        assertScope(row, owner);
        var observed = surveyCodec.body(row.getSurveySnapshot());
        require(MesPhysicalPositionSurveyCodec.VERSION.equals(row.getSurveyVersion())
                && row.getSurveyFingerprint().equals(codec.fingerprint(row.getSurveyVersion(), row.getSurveySnapshot()))
                && MesPhysicalPositionSurveyCodec.EVIDENCE_VERSION.equals(row.getEvidenceVersion())
                && row.getEvidenceFingerprint().equals(codec.fingerprint(row.getEvidenceVersion(), row.getEvidenceSnapshot()))
                && row.getActorId().toString().equals(observed.getExpectedActorUserId())
                && row.getTenantId().toString().equals(observed.getExpectedTenantId()) && row.getFactoryCode().equals(observed.getExpectedFactoryCode())
                && Long.valueOf(1).equals(row.getPhysicalObjectCount()) && row.getObservedTime() != null && row.getRegistrationXid() != null,
                "永久位置观察版本、原文SHA或原实名/组织损坏");
        var evidence = codec.objectDocument(row.getEvidenceSnapshot(), MesPhysicalPositionSurveyCodec.MAX_EVIDENCE);
        require(row.getSurveySnapshot().equals(evidence.path("surveySnapshot").textValue())
                && row.getSurveyFingerprint().equals(evidence.path("surveyFingerprint").textValue())
                && row.getCanonicalIdentifier().equals(evidence.path("canonicalIdentifier").textValue())
                && row.getLayoutCode().equals(evidence.path("layoutCode").textValue())
                && row.getStandardId().toString().equals(evidence.path("standardId").textValue())
                && row.getStandardFingerprint().equals(evidence.path("standardFingerprint").textValue()), "永久主字段与原完整证据不同");
        var ledger = codec.objectDocument(row.getLedgerSnapshot(), 128 * 1024 * 1024);
        verifyChildren(row, observed, evidence, ledger);
        var eventNodes = ledger.path("operations");
        require(eventNodes.isArray() && eventNodes.size() == row.getRevision() && eventNodes.size() >= 1 && eventNodes.size() <= 2, "永久操作全集与原主状态不一致");
        var events = new ArrayList<MesPhysicalPositionSurveyDTO.Event>();
        for (int index = 0; index < eventNodes.size(); index++) {
            var node = eventNodes.get(index);
            assertChildScope(node, row);
            String version = node.path("request_version").textValue();
            String raw = node.path("request_snapshot").textValue();
            require(node.path("revision").asLong() == index + 1L && Objects.equals(node.path("request_fingerprint").textValue(), codec.fingerprint(version, raw)), "永久事件修订序或自身全文SHA不一致");
            var event = new MesPhysicalPositionSurveyDTO.Event();
            event.setEventId(node.path("id").asText());
            event.setOperationRequestId(node.path("request_id").textValue());
            event.setActorUserId(node.path("actor_id").asText());
            event.setActorCode(node.path("actor_code").textValue());
            event.setAction(node.path("action").textValue());
            event.setRevision(Long.toString(index + 1L));
            event.setRequestSnapshot(raw);
            event.setRequestFingerprint(node.path("request_fingerprint").textValue());
            event.setOccurredTime(node.path("occurred_time").textValue());
            event.setVerificationSnapshot(node.path("verification_snapshot").textValue());
            event.setVerificationFingerprint(node.path("verification_fingerprint").textValue());
            if (index == 0) {
                require("OBSERVE".equals(event.getAction()) && row.getRequestId().equals(event.getOperationRequestId())
                        && row.getSurveySnapshot().equals(raw) && row.getSurveyVersion().equals(version)
                        && row.getActorId().toString().equals(event.getActorUserId()) && row.getActorCode().equals(event.getActorCode()), "原采集事件不属于原主账本人/正文");
            } else {
                var decision = surveyCodec.decision(raw);
                require(MesPhysicalPositionSurveyCodec.DECISION_VERSION.equals(version) && event.getAction().equals(decision.getAction())
                        && row.getSurveyFingerprint().equals(decision.getSurveyFingerprint())
                        && row.getEvidenceFingerprint().equals(decision.getEvidenceFingerprint())
                        && row.getTenantId().toString().equals(decision.getExpectedTenantId())
                        && row.getFactoryCode().equals(decision.getExpectedFactoryCode()) && event.getActorUserId().equals(decision.getExpectedActorUserId()), "原决策事件与实名/组织/原SHA不一致");
                boolean cancel = "CANCEL".equals(event.getAction());
                require(cancel ? row.getActorId().toString().equals(event.getActorUserId()) && row.getActorCode().equals(event.getActorCode())
                        : !row.getActorId().toString().equals(event.getActorUserId()) && !row.getActorCode().equals(event.getActorCode()), "永久决策本人/异人证明损坏");
                require(("CONFIRM".equals(event.getAction()) ? "CONFIRMED" : "REJECT".equals(event.getAction()) ? "REJECTED" : "CANCELLED").equals(row.getStatus()), "永久决策事件与主状态不一致");
            }
            if ("CONFIRM".equals(event.getAction())) {
                require(event.getVerificationSnapshot() != null && Objects.equals(event.getVerificationFingerprint(), codec.fingerprint(
                        MesPhysicalTransformSourceFreezeService.BUNDLE_VERSION, event.getVerificationSnapshot())), "原确认完整重读证据缺失/损坏");
            } else require(event.getVerificationSnapshot() == null && event.getVerificationFingerprint() == null, "非确认事件不能附假重读包");
            events.add(event);
        }
        require(row.getLastOperationId().toString().equals(events.get(events.size() - 1).getEventId())
                && (row.getRevision() == 1L ? "OBSERVED".equals(row.getStatus()) && row.getDecidedTime() == null
                : row.getDecidedTime() != null && !row.getDecidedTime().isBefore(row.getObservedTime())), "原主账最后操作或终态时间损坏");
        var receipt = absent(row.getRequestId(), owner, row.getStatus());
        receipt.setSurveyId(row.getId().toString());
        receipt.setObserverUserId(row.getActorId().toString());
        receipt.setObserverCode(row.getActorCode());
        receipt.setSurveySnapshot(row.getSurveySnapshot());
        receipt.setSurveyFingerprint(row.getSurveyFingerprint());
        receipt.setEvidenceSnapshot(row.getEvidenceSnapshot());
        receipt.setEvidenceFingerprint(row.getEvidenceFingerprint());
        receipt.setObservedTime(row.getObservedTime().toString());
        receipt.setDecidedTime(row.getDecidedTime() == null ? null : row.getDecidedTime().toString());
        receipt.setEvents(List.copyOf(events));
        receipt.setPersisted(true);
        receipt.setMessage("永久完整位置观察及原决策可查证；即便CONFIRMED也只确认现场观察，不代表GOOD、库存化、位置可消费余额、全资格或转换许可");
        return receipt;
    }

    /** 双向集合比对全部来源/位置/分配，不以计数或首条source代替完整原账。 */
    private void verifyChildren(MesPhysicalPositionSurveyEntity row, MesPhysicalPositionSurveyDTO.Body body, JsonNode evidence, JsonNode ledger) {
        var expectedSources = new HashSet<String>();
        for (var entry : originalBundle(row).path("sources")) {
            var source = entry.path("source");
            expectedSources.add(tuple(entry.path("stockId").textValue(), source.path("sourceReportId").textValue(),
                    entry.path("sourceVersion").textValue(), entry.path("sourceSnapshot").textValue(), entry.path("sourceFingerprint").textValue(),
                    quantity(source.path("quantity").textValue()), source.path("unit").textValue(),
                    quantity(evidence.path("sourceEquivalents").path(entry.path("stockId").textValue()).textValue())));
        }
        var actualSources = new HashSet<String>();
        for (var node : ledger.path("sources")) {
            assertChildScope(node, row);
            require(actualSources.add(tuple(node.path("stock_id").asText(), node.path("source_report_id").asText(),
                    node.path("source_version").textValue(), node.path("source_snapshot").textValue(), node.path("source_fingerprint").textValue(),
                    quantity(node.path("native_quantity").asText()), node.path("native_unit").textValue(), quantity(node.path("equivalent_quantity").asText()))), "来源原账存在重复完整身份");
        }
        require(expectedSources.size() == body.getSources().size() && expectedSources.equals(actualSources), "永久真实来源全集与原完整证据不一致");
        var expectedPositions = new HashSet<String>();
        var expectedEdges = new HashSet<String>();
        for (var slot : body.getPositions()) {
            String count = "UNKNOWN".equals(slot.getPresence()) ? null : "MISSING".equals(slot.getPresence()) ? "0" : "1";
            expectedPositions.add(tuple(slot.getPositionCode(), slot.getPresence(), count));
            for (var edge : slot.getAllocations()) expectedEdges.add(tuple(slot.getPositionCode(), edge.getStockId(), quantity(edge.getQuantity())));
        }
        var actualPositions = new HashSet<String>();
        for (var slot : ledger.path("positions")) {
            assertChildScope(slot, row);
            require(actualPositions.add(tuple(slot.path("position_code").textValue(), slot.path("presence").textValue(),
                    slot.path("physical_count").isNull() ? null : slot.path("physical_count").asText())), "永久位置重复");
        }
        var actualEdges = new HashSet<String>();
        for (var edge : ledger.path("allocations")) {
            assertChildScope(edge, row);
            require(actualEdges.add(tuple(edge.path("position_code").textValue(), edge.path("stock_id").asText(),
                    quantity(edge.path("equivalent_quantity").asText()))), "永久位置来源边重复");
        }
        require(expectedPositions.equals(actualPositions) && expectedEdges.equals(actualEdges), "永久逐位/全部来源分配与原正文不一致，不按部分记录恢复成功");
    }

    private JsonNode originalBundle(MesPhysicalPositionSurveyEntity row) {
        var evidence = codec.objectDocument(row.getEvidenceSnapshot(), MesPhysicalPositionSurveyCodec.MAX_EVIDENCE);
        String raw = evidence.path("sourceBundleSnapshot").textValue();
        require(Objects.equals(evidence.path("sourceBundleFingerprint").textValue(), codec.fingerprint(MesPhysicalTransformSourceFreezeService.BUNDLE_VERSION, raw)), "原来源包完整SHA不一致");
        var bundle = codec.objectDocument(raw, MesPhysicalPositionSurveyCodec.MAX_DOCUMENT);
        require(MesPhysicalTransformSourceFreezeService.BUNDLE_VERSION.equals(bundle.path("version").textValue())
                && row.getTenantId().toString().equals(bundle.path("tenantId").textValue()) && row.getFactoryCode().equals(bundle.path("factoryCode").textValue())
                && row.getActorId().toString().equals(bundle.path("actorUserId").textValue()) && row.getActorCode().equals(bundle.path("actorCode").textValue()), "原完整来源包版本/归属/实名不一致");
        return bundle;
    }

    private String quantity(String value) {
        return codec.amount(codec.quantity(value, false, "永久位置/来源正量"));
    }

    private String tuple(String... values) {
        return codec.snapshot(java.util.Arrays.asList(values));
    }

    private void assertChildScope(JsonNode child, MesPhysicalPositionSurveyEntity row) {
        require(child.path("tenant_id").asText().equals(row.getTenantId().toString())
                && Objects.equals(child.path("factory_code").textValue(), row.getFactoryCode())
                && child.path("survey_id").asText().equals(row.getId().toString()) && "0".equals(child.path("del_flag").textValue()), "原子事实scope/逻辑删除损坏，不能漏历史恢复");
    }

    private void assertScope(MesPhysicalPositionSurveyEntity row, Identity owner) {
        require(row != null && Objects.equals(owner.tenantId(), row.getTenantId()) && Objects.equals(owner.factoryCode(), row.getFactoryCode())
                && "0".equals(row.getDelFlag()), "原观察不属于当前实际租户/工厂或原账已损坏");
    }

    private void assertExpected(String tenant, String factory, String actor, Identity owner) {
        require(owner.tenantId().toString().equals(tenant) && owner.factoryCode().equals(factory) && owner.actorId().toString().equals(actor), "原声明与当前实际租户/工厂/本人不同");
    }

    private MesPhysicalPositionOperationEntity event(Identity owner, Long surveyId, String requestId, String action, Long revision,
                                                    String raw, String version, LocalDateTime time) {
        var row = new MesPhysicalPositionOperationEntity().setFactoryCode(owner.factoryCode()).setSurveyId(surveyId).setRequestId(requestId)
                .setAction(action).setRevision(revision).setActorId(owner.actorId()).setActorCode(owner.actorCode()).setRequestVersion(version)
                .setRequestSnapshot(raw).setRequestFingerprint(codec.fingerprint(version, raw)).setOccurredTime(time);
        initialize(row, owner, IdWorker.getId());
        return row;
    }

    private void initialize(BaseEntity<?> row, Identity owner, Long id) {
        row.setId(id);
        row.setTenantId(owner.tenantId());
        row.setCreateBy(owner.actorCode());
        row.setDelFlag("0");
    }

    /**
     * 仅服务器创建批量传输标量；不把实体反射字段、客户端ID或当前模板混入永久原记录。
     * ID/精确数量用文本，时间由一次采集生成；此记录集不是新的公开命令或可编辑原文。
     */
    private Map<String, Object> batchRecord(BaseEntity<?> row, Long surveyId, Identity owner, LocalDateTime time) {
        var record = new TreeMap<String, Object>();
        record.put("id", row.getId().toString());
        record.put("tenant_id", owner.tenantId().toString());
        record.put("factory_code", owner.factoryCode());
        record.put("survey_id", surveyId.toString());
        record.put("create_by", owner.actorCode());
        record.put("create_time", time.toString());
        return record;
    }

    private MesPhysicalPositionSurveyDTO.Receipt absent(String requestId, Identity owner, String status) {
        var result = new MesPhysicalPositionSurveyDTO.Receipt();
        result.setRequestId(requestId);
        result.setTenantId(owner.tenantId().toString());
        result.setFactoryCode(owner.factoryCode());
        result.setStatus(status);
        result.setPersisted(false);
        result.setExecutionAvailable(false);
        result.setEvents(List.of());
        result.setMessage("UNAVAILABLE".equals(status) ? "永久位置账未部署/不可核验，不解释为ABSENT；保留完整原号原文查证"
                : "独立当前原账未见采集，不证明此前没有投递或事务结果；不能自动另号重投");
        return result;
    }

    private Identity identity() {
        String factory = codec.code(factoryAccess.require("write"), "实际工厂", 100);
        var user = SecurityUtils.getUser();
        require(user != null && user.getId() != null && TenantContextHolder.getTenantId() != null, "位置采集需要实际租户/工厂/实名");
        codec.id(TenantContextHolder.getTenantId().toString());
        codec.id(user.getId().toString());
        return new Identity(TenantContextHolder.getTenantId(), factory, user.getId(), codec.code(user.getUsername(), "实际实名账号", 100));
    }

    private void requestLock(Identity owner, String requestId) {
        require(Boolean.TRUE.equals(baseMapper.tryRequest(codec.snapshot(List.of(REQUEST_LOCK_VERSION, owner.tenantId().toString(), requestId)))),
                "原位置请求号仍由另一事务处理；保留原号查证，不猜失败后重投");
    }

    private void requireAvailable() {
        require(Boolean.TRUE.equals(baseMapper.available()), "请先部署V2新增位置永久原账，未部署不能执行登记或决策");
    }

    private void requireReady() {
        require(Boolean.TRUE.equals(baseMapper.readinessAvailable()) && Boolean.TRUE.equals(baseMapper.ready()), "实际位置immutable/提交全集/原事件及全部scope守卫未齐备，禁止写入");
    }

    private <T> T independent(Supplier<T> work) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "位置采集入口禁止旧外层事务快照");
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(status -> work.get());
    }

    private record Identity(Long tenantId, String factoryCode, Long actorId, String actorCode) {
    }
}
