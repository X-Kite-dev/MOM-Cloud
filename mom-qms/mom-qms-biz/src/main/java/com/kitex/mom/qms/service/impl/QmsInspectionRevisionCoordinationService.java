package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionHistorySupport.require;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesInspectionRevisionAdmissionDTO;
import com.kitex.mom.qms.dto.*;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.QmsInspectionRevisionCoordinationMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** 本地父锁中的准备/取消意图及队列；网络由独立工作器承担。 */
@Service
@RequiredArgsConstructor
public class QmsInspectionRevisionCoordinationService {
    private final QmsInspectionRevisionCoordinationMapper mapper;
    private final QmsInspectionRevisionService revisions;
    private final QmsInspectionHistorySupport access;

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public QmsInspectionRevisionCoordinationDTO prepare(Long task, Long id) {
        revisions.coordinateParent(task);
        var existing = mapper.execution(id, tenant(), factory());
        if (existing != null) {
            require(
                    Objects.equals(existing.getTaskId(), task)
                            && Objects.equals(existing.getExecutorId(), actor()),
                    "既有执行准备不能换任务或执行人");
            return view(task, id);
        }
        Date now = new Date();
        String raw = revisions.freezeExecution(task, id, actor(), now);
        var e =
                new QmsInspectionRevisionExecutionEntity()
                        .setId(id)
                        .setTenantId(tenant())
                        .setFactoryCode(factory())
                        .setTaskId(task)
                        .setExecutorId(actor())
                        .setCreatedAt(now)
                        .setIntent(raw)
                        .setIntentFingerprint(QmsInspectionRevisionCoordinationCodec.hash(raw));
        require(mapper.insert(e) == 1, "执行意图未保存，准备已回滚");
        require(
                mapper.insertDelivery(
                                new QmsInspectionRevisionDeliveryEntity()
                                        .setId(id)
                                        .setTenantId(tenant())
                                        .setFactoryCode(factory())
                                        .setTaskId(task)
                                        .setOperation("RESERVE")
                                        .setStatus("PENDING")
                                        .setAttemptCount(0)
                                        .setNextAttemptAt(now)
                                        .setUpdatedAt(now)
                                        .setVersion(0L))
                        == 1,
                "准备投递队列未保存，原意图已回滚");
        return view(task, id);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public QmsInspectionRevisionCoordinationDTO cancel(
            Long task, Long id, QmsInspectionRevisionCancelCommand command) {
        access.requireWrite();
        installed();

        require(mapper.runPhase(id, tenant(), factory()) == null, "已进入正式执行，请使用正式中止/恢复入口");
        var e = execution(task, id);
        var d = mapper.delivery(id, tenant(), factory(), true);
        require(d != null, "缺少准备投递进度");
        var reason = command == null ? null : command.reason();
        require(
                reason != null && !reason.isBlank() && reason.strip().length() <= 1000,
                "取消原因须1至1000字符");
        reason = reason.strip();
        var old = mapper.cancel(id, tenant(), factory());
        if (old != null) {
            QmsInspectionRevisionCoordinationCodec.cancel(e, old);
            require(
                    Objects.equals(old.getCanceledBy(), actor()) && old.getReason().equals(reason),
                    "同号取消不能换人或换原因");
            return view(task, id);
        }
        require(!"CANCELED".equals(d.getStatus()), "已有取消终态但缺少原件，请先核账");
        Date now = new Date();
        require(!now.before(e.getCreatedAt()), "当前时钟早于执行意图，禁止倒签");
        require(
                mapper.insertCancel(
                                new QmsInspectionRevisionCancelEntity()
                                        .setId(id)
                                        .setTenantId(tenant())
                                        .setFactoryCode(factory())
                                        .setTaskId(task)
                                        .setCanceledBy(actor())
                                        .setCanceledAt(now)
                                        .setReason(reason))
                        == 1,
                "取消意图未保存");
        d.setOperation("CANCEL")
                .setStatus("PENDING")
                .setAttemptCount(0)
                .setNextAttemptAt(now)
                .setLastError(null)
                .setUpdatedAt(now);
        require(mapper.progress(d) == 1, "取消队列未保存，取消意图已回滚");
        return view(task, id);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public QmsInspectionRevisionCoordinationDTO retry(Long task, Long id) {
        access.requireWrite();
        installed();

        require(mapper.runPhase(id, tenant(), factory()) == null, "正式执行不能从准备入口重排");
        execution(task, id);
        var d = mapper.delivery(id, tenant(), factory(), true);
        require(
                d != null && Set.of("ATTENTION", "RETRY_WAIT").contains(d.getStatus()),
                "当前进度不支持人工原件重排队");
        view(task, id);
        d.setStatus("PENDING")
                .setAttemptCount(0)
                .setNextAttemptAt(new Date())
                .setUpdatedAt(new Date())
                .setLastError(null);
        require(mapper.progress(d) == 1, "原件重排队失败");
        return view(task, id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QmsInspectionRevisionCoordinationDTO state(Long task, Long id) {
        access.requireRead();
        installed();
        if (mapper.execution(id, tenant(), factory()) == null) revisions.detail(task, id);
        return view(task, id);
    }

    void validateFormalStart(Long task, Long id) {
        var e = execution(task, id);
        require(
                e.getIntent()
                        .equals(
                                revisions.freezeExecution(
                                        task, id, e.getExecutorId(), e.getCreatedAt())),
                "当前依据已变化，不能沿用旧准备正式执行");
    }

    private QmsInspectionRevisionExecutionEntity execution(Long task, Long id) {
        require(id != null && id > 0, "执行准备申请号无效");
        var e = mapper.execution(id, tenant(), factory());
        require(e != null && Objects.equals(task, e.getTaskId()), "当前组织/任务不存在该执行准备");
        QmsInspectionRevisionCoordinationCodec.owner(
                e,
                mapper.request(e.getId(), tenant(), factory()),
                mapper.review(e.getId(), tenant(), factory()));
        return e;
    }

    private QmsInspectionRevisionCoordinationDTO view(Long task, Long id) {
        var e = mapper.execution(id, tenant(), factory());
        if (e == null)
            return new QmsInspectionRevisionCoordinationDTO(
                    "QMS_REVISION_COORDINATION_V1",
                    tenant().toString(),
                    factory(),
                    actor().toString(),
                    task.toString(),
                    id.toString(),
                    null,
                    null,
                    "ABSENT",
                    0,
                    null,
                    null,
                    null,
                    false,
                    null);
        require(Objects.equals(task, e.getTaskId()), "执行准备不属于该任务");
        QmsInspectionRevisionCoordinationCodec.owner(
                e,
                mapper.request(e.getId(), tenant(), factory()),
                mapper.review(e.getId(), tenant(), factory()));
        var c = mapper.cancel(id, tenant(), factory());
        QmsInspectionRevisionCoordinationCodec.cancel(e, c);
        var d = mapper.delivery(id, tenant(), factory(), false);
        require(
                d != null
                        && Objects.equals(d.getTaskId(), task)
                        && Objects.equals(d.getOperation(), c == null ? "RESERVE" : "CANCEL")
                        && d.getAttemptCount() != null
                        && d.getAttemptCount() >= 0
                        && d.getAttemptCount() <= 8
                        && d.getVersion() != null
                        && d.getVersion() >= 0
                        && Set.of("PENDING", "RETRY_WAIT", "ATTENTION", "RESERVED", "CANCELED")
                                .contains(Objects.toString(d.getStatus(), "")),
                "执行准备投递进度损坏");
        var receipts = mapper.receipts(id, tenant(), factory());
        require(receipts.size() <= 2, "协调回执超过原件范围");
        MesInspectionRevisionAdmissionDTO last = null, reserved = null, canceled = null;
        Set<String> operations = new HashSet<>();
        for (var r : receipts) {
            require(operations.add(r.getOperation()), "协调操作存在重复回执");
            var a = QmsInspectionRevisionCoordinationCodec.original(e, c, r);
            if ("RESERVE".equals(r.getOperation())) reserved = a;
            else canceled = a;
            if (r.getOperation().equals(d.getOperation())) last = a;
        }
        if (canceled != null) QmsInspectionRevisionCoordinationCodec.continuity(reserved, canceled);
        String formal = mapper.runPhase(id, tenant(), factory());
        if (formal != null && !"STOPPED".equals(formal))
            return new QmsInspectionRevisionCoordinationDTO(
                    "QMS_REVISION_COORDINATION_V1",
                    tenant().toString(),
                    factory(),
                    actor().toString(),
                    task.toString(),
                    id.toString(),
                    e.getIntentFingerprint(),
                    d.getOperation(),
                    "EXECUTION_OWNED",
                    d.getAttemptCount(),
                    null,
                    "已进入正式执行，请查看执行状态",
                    null,
                    false,
                    null);
        boolean terminal = Set.of("RESERVED", "CANCELED").contains(d.getStatus());
        require(
                terminal == (last != null) && (!terminal || d.getStatus().equals(last.getStatus())),
                "进度与永久回执不一致");
        return new QmsInspectionRevisionCoordinationDTO(
                "QMS_REVISION_COORDINATION_V1",
                tenant().toString(),
                factory(),
                actor().toString(),
                task.toString(),
                id.toString(),
                e.getIntentFingerprint(),
                d.getOperation(),
                d.getStatus(),
                d.getAttemptCount(),
                d.getNextAttemptAt(),
                d.getLastError(),
                last == null ? null : last.isHeld(),
                false,
                last == null
                        ? null
                        : new QmsInspectionRevisionCoordinationDTO.Receipt(
                                last.getStatus(),
                                Objects.toString(last.getGenRecordId(), null),
                                last.getAdmissionFingerprint(),
                                last.getReservedAt(),
                                last.getCanceledAt(),
                                Objects.toString(last.getCanceledBy(), null),
                                last.getCancelReason()));
    }

    private void installed() {
        QmsInspectionEventWorker.scope();
        require(Integer.valueOf(1).equals(mapper.ready()), "请先安装并核验修订协调结构364");
    }

    private static Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    private static String factory() {
        return FactoryContextHolder.getFactoryCode();
    }

    private static Long actor() {
        var u = SecurityUtils.getUser();
        require(u != null && u.getId() != null && u.getId() > 0, "执行准备须实名登录");
        return u.getId();
    }
}
