package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.dto.inspection.QmsProcedureDispositionContract.*;
import static com.kitex.mom.qms.service.impl.QmsInspectionHistorySupport.require;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.*;
import com.kitex.common.core.jackson.MomJavaTimeModule;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.qms.dto.*;
import com.kitex.mom.qms.dto.inspection.InspectionProcedureSourceContract;
import com.kitex.mom.qms.dto.resp.HandleFinishDTO;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;
import com.kitex.mom.qms.service.QmsPlanEventOutboxService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** 普通MES工序接受处置；实际身份与前件核对后，原件/任务/历史/事件同事务提交。 */
@Service
@RequiredArgsConstructor
public class QmsProcedureDispositionService {
    private static final ObjectMapper JSON =
            new ObjectMapper().registerModule(new MomJavaTimeModule());
    private static final String VERSION = "QMS_PROCEDURE_DISPOSITION_V1";
    private final QmsProcedureDispositionMapper mapper;
    private final QmsInspectionEventMapper events;
    private final QmsInspectionEventService journal;
    private final QmsPlanEventOutboxService plan;
    private final QmsInspectionHistorySupport access;

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean handleIfManaged(HandleFinishDTO command) {
        access.requireScope();
        require(command != null && command.getId() != null && command.getId() > 0, "处置任务无效");
        String request = command.getRequestId() == null ? null : command.getRequestId().strip();
        var observed = mapper.parent(command.getId(), tenant(), factory());
        if (observed != null && !managed(observed)) {
            require(
                    command.getExpectedEventId() == null
                            && command.getExpectedFingerprint() == null,
                    "任务来源已变化，不能把正式处置降级到旧入口");
            return false;
        }
        access.requireWrite();
        installed();
        var old = request == null ? null : mapper.receipt(tenant(), factory(), request);
        if (old != null) {
            replay(old, command);
            return true;
        }
        var task = mapper.parentLocked(command.getId(), tenant(), factory());
        require(managed(task), "处置任务来源已变化");
        old = mapper.receipt(tenant(), factory(), request);
        if (old != null) {
            replay(old, command);
            return true;
        }
        require(
                mapper.linkedCases(task.getId(), tenant(), factory()) == 0,
                "已关联NCR，须使用案件的MRB执行与验证流程，不能从工序处置直接放行");
        require(
                mapper.activeRevision(task.getId(), tenant(), factory()) == 0,
                "质检修订尚未完成现场同步，请先恢复原流程");
        var chain = journal.originals("PROCEDURE", task.getId());
        require(!chain.isEmpty(), "缺少原检验事件，不能从当前结论补造处置");
        var head = chain.get(chain.size() - 1);
        require(
                Objects.equals(command.getExpectedEventId(), head.getId())
                        && Objects.equals(command.getExpectedFingerprint(), head.getFingerprint()),
                "处置依据已变化，请重新读取可处置数量");
        JsonNode before = tree(head.getPayload()).get("source");
        require(
                before.equals(
                        source(events.procedureSnapshot(tenant(), factory(), task.getId(), false))),
                "当前任务与正式事件不一致，禁止补猜处置依据");
        Long receiptId = IdWorker.getId(),
                recordId = IdWorker.getId(),
                historyId = IdWorker.getId(),
                eventId = IdWorker.getId(),
                actor = actor();
        Date now = new Date();
        var user = SecurityUtils.getUser();
        String name = user.getName();
        if (name == null || name.isBlank()) name = user.getUsername();
        if (name == null || name.isBlank()) name = actor.toString();
        String raw =
                create(
                        new Identity(
                                receiptId,
                                tenant(),
                                factory(),
                                task.getId(),
                                recordId,
                                historyId,
                                actor,
                                name,
                                now,
                                SecurityUtils.getAuthentication().getName()),
                        command,
                        head.getId(),
                        head.getFingerprint(),
                        before);
        var proof = parse(raw);
        var after = proof.after();
        var record =
                new QmsInspectionProcedureHandleRecordEntity()
                        .setTaskId(task.getId())
                        .setRequestId(proof.requestId())
                        .setHandleNum(proof.quantity())
                        .setHandleResult(proof.kind())
                        .setHandleRemark(proof.reason())
                        .setHandlePersonId(actor)
                        .setHandlePersonName(name)
                        .setHandleTime(now)
                        .setStatus(true);
        record.setId(recordId);
        record.setTenantId(tenant());
        record.setFactoryCode(factory());
        record.setCreateBy(actor.toString());
        require(mapper.insertRecord(record) == 1, "处置明细未保存，已整体回滚");
        var history =
                new QmsInspectionProcedureHistoryEntity()
                        .setTaskId(task.getId())
                        .setType("DISPOSITION")
                        .setNum(proof.quantity())
                        .setTime(now)
                        .setDetailName(proof.kind() + "#" + recordId);
        history.setId(historyId);
        history.setTenantId(tenant());
        history.setFactoryCode(factory());
        history.setCreateBy(actor.toString());
        require(mapper.insertHistory(history) == 1, "处置历史未保存，已整体回滚");
        require(
                mapper.insert(
                                new QmsProcedureDispositionReceiptEntity()
                                        .setId(receiptId)
                                        .setTenantId(tenant())
                                        .setFactoryCode(factory())
                                        .setTaskId(task.getId())
                                        .setRequestId(proof.requestId())
                                        .setHandleRecordId(recordId)
                                        .setHistoryId(historyId)
                                        .setEventId(eventId)
                                        .setPayload(raw)
                                        .setFingerprint(hash(raw))
                                        .setCreatedAt(now))
                        == 1,
                "处置原件未保存，已整体回滚");
        task.setUsableNum(after.path("usableNum").intValue())
                .setHandleNum(after.path("handleNum").intValue())
                .setHandleResult(after.path("handleResult").textValue())
                .setResult(after.path("result").textValue())
                .setStatus(after.path("status").textValue());
        require(
                mapper.updateTask(
                                task,
                                before.path("usableNum").intValue(),
                                before.path("handleNum").intValue(),
                                actor.toString())
                        == 1,
                "处置累计未保存，已整体回滚");
        var event = journal.captureDisposition(historyId, raw, eventId);
        require(event != null && Objects.equals(event.getId(), eventId), "处置未形成正式连续事件");
        var planned = plan.enqueueDisposition("PROCEDURE", task.getId(), recordId);
        require(planned != null && planned.getEventId() != null, "处置计划待发未保存，已整体回滚");
        return true;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QmsProcedureDispositionBasisDTO basis(Long id) {
        access.requireRead();
        var task = mapper.parent(id, tenant(), factory());
        require(task != null, "当前组织不存在该检验任务");
        if (!managed(task)) return basis(task, "LEGACY", false, null, null);
        installed();
        var chain = journal.originals("PROCEDURE", id);
        var head = chain.isEmpty() ? null : chain.get(chain.size() - 1);
        String reason = null;
        try {
            require(head != null, "没有正式判定原件");
            JsonNode before = tree(head.getPayload()).get("source");
            require(
                    before.equals(source(events.procedureSnapshot(tenant(), factory(), id, false))),
                    "任务与正式原件不一致");
            validateSource(before);
            require(
                    "UN_QUALIFIED".equals(task.getResult())
                            && "UNHANDLED".equals(task.getStatus())
                            && task.getHandleNum() != null
                            && task.getUsableNum() != null
                            && task.getNum() != null
                            && task.getHandleNum() < task.getNum()
                            && task.getHandleNum().equals(task.getUsableNum()),
                    "当前没有可通过本入口接受的待处置量");
            require(mapper.linkedCases(id, tenant(), factory()) == 0, "已关联NCR，请使用案件的MRB执行与验证流程");
            require(mapper.activeRevision(id, tenant(), factory()) == 0, "修订尚在现场同步，请先恢复修订");
        } catch (RuntimeException invalid) {
            reason = invalid.getMessage();
        }
        return basis(task, "EVENT", reason == null, reason, head);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QmsProcedureDispositionReceiptDTO lookup(Long task, String request) {
        return lookup(task, request, null, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QmsProcedureDispositionReceiptDTO lookup(
            Long task, String request, Long expectedEventId, String expectedFingerprint) {
        access.requireRead();
        installed();
        require(
                task != null
                        && task > 0
                        && request != null
                        && request.matches("[A-Za-z0-9_-]{16,100}"),
                "处置查询标识无效");
        require(
                expectedEventId == null && expectedFingerprint == null
                        || expectedEventId != null
                                && expectedEventId > 0
                                && expectedFingerprint != null
                                && expectedFingerprint.matches("[0-9a-f]{64}"),
                "查询前件不完整");
        var row = mapper.receipt(tenant(), factory(), request);
        if (row == null) {
            require(mapper.parent(task, tenant(), factory()) != null, "当前组织不存在该检验任务");
            boolean stale = false;
            if (expectedEventId != null) {
                var chain = journal.originals("PROCEDURE", task);
                if (!chain.isEmpty()) {
                    var head = chain.get(chain.size() - 1);
                    stale =
                            !Objects.equals(head.getId(), expectedEventId)
                                    || !Objects.equals(head.getFingerprint(), expectedFingerprint);
                }
            }
            return new QmsProcedureDispositionReceiptDTO(
                    VERSION,
                    str(tenant()),
                    factory(),
                    str(actor()),
                    str(task),
                    request,
                    false,
                    stale,
                    str(expectedEventId),
                    expectedFingerprint,
                    null);
        }
        require(Objects.equals(row.getTaskId(), task), "处置请求不属于当前任务");
        Parsed p = audit(row);
        JsonNode j = p.body();
        var receipt =
                new QmsProcedureDispositionReceiptDTO.Receipt(
                        str(task),
                        p.requestId(),
                        p.quantity(),
                        p.kind(),
                        p.reason(),
                        p.authorizationRef(),
                        p.concessionUnrestricted(),
                        j.path("previousEventId").textValue(),
                        j.path("previousFingerprint").textValue(),
                        str(row.getId()),
                        str(row.getEventId()),
                        str(p.actorId()),
                        p.after().path("handleNum").intValue(),
                        p.after().path("usableNum").intValue(),
                        p.after().path("result").textValue(),
                        mapper.delivery(row.getEventId(), tenant(), factory()));
        return new QmsProcedureDispositionReceiptDTO(
                VERSION,
                str(tenant()),
                factory(),
                str(actor()),
                str(task),
                request,
                true,
                false,
                str(expectedEventId),
                expectedFingerprint,
                receipt);
    }

    private void replay(QmsProcedureDispositionReceiptEntity row, HandleFinishDTO command) {
        var p = audit(row);
        require(matches(p, command, actor()), "同一处置请求已用于不同任务、人员、数量、原因或授权，不能换件重送");
    }

    private Parsed audit(QmsProcedureDispositionReceiptEntity row) {
        require(
                Objects.equals(row.getTenantId(), tenant())
                        && Objects.equals(row.getFactoryCode(), factory())
                        && row.getPayload() != null
                        && hash(row.getPayload()).equals(row.getFingerprint())
                        && row.getCreatedAt() != null,
                "处置原件组织或SHA损坏");
        Parsed p = parse(row.getPayload());
        JsonNode j = p.body();
        require(
                str(row.getId()).equals(j.path("receiptId").textValue())
                        && str(row.getTaskId()).equals(j.path("taskId").textValue())
                        && row.getRequestId().equals(p.requestId())
                        && str(row.getHandleRecordId()).equals(j.path("handleRecordId").textValue())
                        && str(row.getHistoryId()).equals(j.path("historyId").textValue())
                        && row.getCreatedAt()
                                .toInstant()
                                .toString()
                                .equals(j.path("handledAt").textValue()),
                "处置原件索引不一致");
        var record = mapper.handleRecord(row.getHandleRecordId(), tenant(), factory());
        var h = mapper.history(row.getHistoryId(), tenant(), factory());
        require(
                record != null
                        && Objects.equals(record.getTaskId(), row.getTaskId())
                        && Objects.equals(record.getRequestId(), p.requestId())
                        && Objects.equals(record.getHandleNum(), p.quantity())
                        && Objects.equals(record.getHandleResult(), p.kind())
                        && Objects.equals(record.getHandleRemark(), p.reason())
                        && Objects.equals(record.getHandlePersonId(), p.actorId())
                        && Objects.equals(
                                record.getHandlePersonName(), j.path("handledByName").textValue())
                        && Objects.equals(record.getHandleTime(), row.getCreatedAt())
                        && h != null
                        && Objects.equals(h.getTaskId(), row.getTaskId())
                        && "DISPOSITION".equals(h.getType())
                        && Objects.equals(h.getNum(), p.quantity())
                        && Objects.equals(h.getTime(), row.getCreatedAt())
                        && h.getQualifiedStatus() == null
                        && Objects.equals(h.getCreateBy(), j.path("recordedBy").textValue()),
                "处置明细或历史与正式原件不一致");
        var event = mapper.event(row.getEventId(), tenant(), factory());
        JsonNode root = QmsInspectionEventDeliveryCodec.raw(event);
        require(
                root.path("dispositionPayload").textValue().equals(row.getPayload())
                        && root.path("dispositionFingerprint")
                                .textValue()
                                .equals(row.getFingerprint())
                        && mapper.delivery(row.getEventId(), tenant(), factory()) != null,
                "处置缺少同件正式事件或可靠发件");
        return p;
    }

    private QmsProcedureDispositionBasisDTO basis(
            QmsInspectionProcedureTaskEntity t,
            String mode,
            boolean eligible,
            String reason,
            QmsInspectionEventEntity event) {
        Integer remaining =
                t.getNum() != null && t.getHandleNum() != null
                        ? t.getNum() - t.getHandleNum()
                        : null;
        return new QmsProcedureDispositionBasisDTO(
                VERSION,
                str(tenant()),
                factory(),
                str(actor()),
                str(t.getId()),
                mode,
                eligible,
                reason,
                t.getNum(),
                t.getHandleNum(),
                t.getUsableNum(),
                remaining,
                event == null ? null : str(event.getId()),
                event == null ? null : event.getFingerprint(),
                t.getResult());
    }

    private JsonNode source(Map<String, Object> map) {
        require(map != null, "缺少实际任务来源");
        JsonNode all = JSON.valueToTree(map);
        Map<String, Object> out = new TreeMap<>();
        for (String k : SOURCE_FIELDS) {
            JsonNode n = all.get(k);
            out.put(k, n == null || n.isNull() ? null : "standardId".equals(k) ? n.asText() : n);
        }
        return JSON.valueToTree(out);
    }

    private boolean managed(QmsInspectionProcedureTaskEntity t) {
        return t != null
                && t.getSourceRequestId() != null
                && t.getSourceRequestId()
                        .startsWith(InspectionProcedureSourceContract.REQUEST_PREFIX);
    }

    private void installed() {
        require(Integer.valueOf(1).equals(mapper.ready()), "请先安装并核验工序处置结构366");
    }

    private static JsonNode tree(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("处置来源原文不可解析", e);
        }
    }

    private static Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    private static String factory() {
        return FactoryContextHolder.getFactoryCode();
    }

    private static Long actor() {
        var u = SecurityUtils.getUser();
        require(u != null && u.getId() != null && u.getId() > 0, "处置须实名登录");
        return u.getId();
    }

    private static String str(Long n) {
        return n == null ? null : n.toString();
    }
}
