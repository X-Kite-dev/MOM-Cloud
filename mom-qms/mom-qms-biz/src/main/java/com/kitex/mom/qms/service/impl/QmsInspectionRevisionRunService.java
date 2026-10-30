package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionOutcomeContract.*;
import static com.kitex.mom.qms.service.impl.QmsInspectionHistorySupport.require;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.qms.dto.*;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
@RequiredArgsConstructor
public class QmsInspectionRevisionRunService {
    private final QmsInspectionRevisionRunMapper runs;
    private final QmsInspectionRevisionCoordinationMapper coordination;
    private final QmsInspectionRevisionCoordinationService preparation;
    private final QmsInspectionHistorySupport access;

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public QmsInspectionRevisionRunDTO start(Long task, Long id) {
        preparation.prepare(task, id);
        preparation.validateFormalStart(task, id);
        installed();
        var e = execution(task, id);
        var d = coordination.delivery(id, tenant(), factory(), true);
        require(
                d != null
                        && "RESERVE".equals(d.getOperation())
                        && coordination.cancel(id, tenant(), factory()) == null,
                "准备已取消，不能正式执行");
        var old = runs.run(id, tenant(), factory(), true);
        if (old == null) {
            Date now = new Date();
            require(
                    runs.insert(
                                    new QmsInspectionRevisionRunEntity()
                                            .setId(id)
                                            .setTenantId(tenant())
                                            .setFactoryCode(factory())
                                            .setTaskId(task)
                                            .setCreatedBy(actor())
                                            .setCreatedAt(now)
                                            .setPhase("PREPARE")
                                            .setStatus("READY")
                                            .setAttemptCount(0)
                                            .setNextAttemptAt(now)
                                            .setUpdatedAt(now)
                                            .setVersion(0L))
                            == 1,
                    "正式执行请求未保存");
        }
        return view(e);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public QmsInspectionRevisionRunDTO abort(
            Long task, Long id, QmsInspectionRevisionCancelCommand command) {
        access.requireWrite();
        installed();
        var e = execution(task, id);
        var d = coordination.delivery(id, tenant(), factory(), true);
        var run = runs.run(id, tenant(), factory(), true);
        require(run != null, "尚未登记正式执行");
        String reason = command == null ? null : command.reason();
        require(
                reason != null && !reason.isBlank() && reason.strip().length() <= 1000,
                "中止原因须1至1000字符");
        reason = reason.strip();
        var outcome = runs.outcome(id, tenant(), factory());
        if (outcome != null) {
            var p = QmsInspectionRevisionRunCodec.outcome(e, outcome);
            require(
                    "ABORTED".equals(p.decision())
                            && Objects.equals(p.abortBy(), actor())
                            && p.reason().equals(reason),
                    "判定已提交或已有不同中止，不得覆盖；请恢复原流程");
            return view(e);
        }
        require(Set.of("PREPARE", "SEAL", "COMMIT").contains(run.getPhase()), "结果已提交，只能恢复同步");
        Date now = new Date();
        var seal = QmsInspectionRevisionRunCodec.seal(runs, e);
        String raw =
                aborted(
                        e.getIntent(),
                        seal == null ? null : seal.getSealFingerprint(),
                        actor(),
                        reason,
                        now);
        require(
                runs.recordOutcome(
                                new QmsInspectionRevisionOutcomeEntity()
                                        .setId(id)
                                        .setTenantId(tenant())
                                        .setFactoryCode(factory())
                                        .setTaskId(task)
                                        .setDecision("ABORTED")
                                        .setPayload(raw)
                                        .setFingerprint(hash(raw))
                                        .setCreatedAt(now))
                        == 1,
                "中止原件未保存");
        require(
                coordination.insertCancel(
                                new QmsInspectionRevisionCancelEntity()
                                        .setId(id)
                                        .setTenantId(tenant())
                                        .setFactoryCode(factory())
                                        .setTaskId(task)
                                        .setCanceledBy(actor())
                                        .setCanceledAt(now)
                                        .setReason(reason))
                        == 1,
                "中止取消意图未保存");
        d.setOperation("CANCEL")
                .setStatus("PENDING")
                .setAttemptCount(0)
                .setNextAttemptAt(now)
                .setLastError(null)
                .setUpdatedAt(now);
        require(coordination.progress(d) == 1, "中止投递未保存");
        ready(run, "ABORT");
        require(runs.progress(run) == 1, "正式中止阶段未保存");
        return view(e);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public QmsInspectionRevisionRunDTO retry(Long task, Long id) {
        access.requireWrite();
        installed();
        var e = execution(task, id);
        var d = coordination.delivery(id, tenant(), factory(), true);
        var run = runs.run(id, tenant(), factory(), true);
        require(
                run != null && Set.of("RETRY_WAIT", "ATTENTION").contains(run.getStatus()),
                "当前无需恢复");
        if ("PREPARE".equals(run.getPhase())) {
            require(d != null && "RESERVE".equals(d.getOperation()), "准备投递已切换");
            d.setStatus("PENDING")
                    .setAttemptCount(0)
                    .setNextAttemptAt(new Date())
                    .setUpdatedAt(new Date())
                    .setLastError(null);
            require(coordination.progress(d) == 1, "准备重排失败");
        }
        ready(run, run.getPhase());
        require(runs.progress(run) == 1, "正式执行恢复失败");
        return view(e);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QmsInspectionRevisionRunDTO state(Long task, Long id) {
        access.requireRead();
        installed();
        var run = runs.run(id, tenant(), factory(), false);
        if (run == null) {
            var prepared = preparation.state(task, id);
            boolean canceled = "CANCEL".equals(prepared.operation());
            return new QmsInspectionRevisionRunDTO(
                    "QMS_REVISION_EXECUTION_V1",
                    tenant().toString(),
                    factory(),
                    actor().toString(),
                    task.toString(),
                    id.toString(),
                    null,
                    canceled ? "PREPARATION_CANCELED" : "ABSENT",
                    false,
                    null,
                    false,
                    0,
                    canceled ? "本次准备已取消，请重新申请" : "尚未执行",
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        return view(execution(task, id));
    }

    private QmsInspectionRevisionRunDTO view(QmsInspectionRevisionExecutionEntity e) {
        var run = runs.run(e.getId(), tenant(), factory(), false);
        require(run != null && Objects.equals(run.getTaskId(), e.getTaskId()), "正式执行不属于该任务");
        var o = runs.outcome(e.getId(), tenant(), factory());
        var proof = o == null ? null : QmsInspectionRevisionRunCodec.outcome(e, o);
        boolean changed = proof != null && "COMMITTED".equals(proof.decision());
        boolean done = "DONE".equals(run.getPhase()), stopped = "STOPPED".equals(run.getPhase());
        var seal = QmsInspectionRevisionRunCodec.seal(runs, e);
        boolean terminal = false;
        for (var row : runs.receipts(e.getId(), tenant(), factory())) {
            var v = QmsInspectionRevisionRunCodec.original(e, row);
            if (!"SEAL".equals(row.getOperation())) {
                QmsInspectionRevisionRunCodec.receipt(e, row.getOperation(), o, seal, v);
                terminal |=
                        done && "APPLY".equals(row.getOperation())
                                || stopped && "ABORT".equals(row.getOperation());
            }
        }
        require(!(done || stopped) || terminal, "正式阶段缺少确切MES终态");
        String status =
                done
                        ? "APPLIED"
                        : stopped
                                ? "ABORTED"
                                : "READY".equals(run.getStatus()) ? "RUNNING" : run.getStatus();
        String message =
                done
                        ? "修订已执行，当前不合格待处置"
                        : stopped ? "执行已中止，未改写原判定" : changed ? "判定已提交，等待现场同步确认" : "修订正在处理中";
        return new QmsInspectionRevisionRunDTO(
                "QMS_REVISION_EXECUTION_V1",
                tenant().toString(),
                factory(),
                actor().toString(),
                e.getTaskId().toString(),
                e.getId().toString(),
                run.getPhase(),
                status,
                changed,
                done || stopped ? Boolean.FALSE : null,
                !changed && !done && !stopped,
                run.getAttemptCount(),
                message,
                run.getLastError(),
                run.getNextAttemptAt(),
                run.getUpdatedAt(),
                changed ? proof.event().path("eventId").textValue() : null,
                o == null ? null : o.getFingerprint());
    }

    private QmsInspectionRevisionExecutionEntity execution(Long task, Long id) {
        var e = QmsInspectionRevisionRunCodec.execution(coordination, id, tenant(), factory());
        require(Objects.equals(task, e.getTaskId()), "正式执行不属于该检验任务");
        return e;
    }

    private void installed() {
        QmsInspectionEventWorker.scope();
        require(Integer.valueOf(1).equals(runs.ready()), "请先安装并核验正式执行结构365");
    }

    static void ready(QmsInspectionRevisionRunEntity r, String phase) {
        r.setPhase(phase)
                .setStatus("READY")
                .setAttemptCount(0)
                .setNextAttemptAt(new Date())
                .setUpdatedAt(new Date())
                .setLastError(null);
    }

    private static Long tenant() {
        return TenantContextHolder.getTenantId();
    }

    private static String factory() {
        return FactoryContextHolder.getFactoryCode();
    }

    private static Long actor() {
        var u = SecurityUtils.getUser();
        require(u != null && u.getId() != null && u.getId() > 0, "正式执行须实名");
        return u.getId();
    }
}
