package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.dto.inspection.QmsInspectionRevisionOutcomeContract.*;
import static com.kitex.mom.qms.service.impl.QmsInspectionHistorySupport.require;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.*;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.*;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

/** 网络只锁投递行→run；不倒取业务父锁。 */
@Service
@RequiredArgsConstructor
public class QmsInspectionRevisionRunWorker {
    private final QmsInspectionRevisionRunMapper runs;
    private final QmsInspectionRevisionCoordinationMapper coordination;
    private final RemoteMesTaskService mes;

    @Value("${mom.qms-inspection-revision.delivery-secret:}")
    private String deliverySecret;

    @Transactional(
            rollbackFor = Exception.class,
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public boolean advance(Long id) {
        QmsInspectionEventWorker.scope();
        require(Integer.valueOf(1).equals(runs.ready()), "正式修订结构未就绪");
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        var d = coordination.delivery(id, tenant, factory, true);
        var run = runs.run(id, tenant, factory, true);
        if (run == null
                || !Set.of("READY", "RETRY_WAIT").contains(run.getStatus())
                || run.getNextAttemptAt() == null
                || run.getNextAttemptAt().after(new Date())) return false;
        var e = QmsInspectionRevisionRunCodec.execution(coordination, id, tenant, factory);
        if ("PREPARE".equals(run.getPhase())) {
            if (d != null && "RESERVED".equals(d.getStatus())) {
                var rs = coordination.receipts(id, tenant, factory);
                require(rs.size() == 1 && "RESERVE".equals(rs.get(0).getOperation()), "正式准备缺少唯一回执");
                QmsInspectionRevisionCoordinationCodec.original(e, null, rs.get(0));
                QmsInspectionRevisionRunService.ready(run, "SEAL");
            } else {
                require(d != null, "缺少准备投递");
                run.setStatus("ATTENTION".equals(d.getStatus()) ? "ATTENTION" : "RETRY_WAIT")
                        .setAttemptCount(d.getAttemptCount())
                        .setNextAttemptAt(d.getNextAttemptAt())
                        .setUpdatedAt(new Date())
                        .setLastError(d.getLastError() == null ? "等待准备确认" : d.getLastError());
            }
            require(runs.progress(run) == 1, "准备确认阶段未保存");
            return "SEAL".equals(run.getPhase());
        }
        require(Set.of("SEAL", "APPLY", "ABORT").contains(run.getPhase()), "当前不是网络执行阶段");
        var o = runs.outcome(id, tenant, factory);
        var seal = QmsInspectionRevisionRunCodec.seal(runs, e);
        if (!"SEAL".equals(run.getPhase())) QmsInspectionRevisionRunCodec.outcome(e, o);
        var command =
                new MesInspectionRevisionExecutionDTO()
                        .setOperation(run.getPhase())
                        .setRequestId(id)
                        .setTenantId(tenant)
                        .setFactoryCode(factory)
                        .setIntent(e.getIntent())
                        .setIntentFingerprint(e.getIntentFingerprint())
                        .setOutcome(o == null ? null : o.getPayload())
                        .setOutcomeFingerprint(o == null ? null : o.getFingerprint())
                        .setIssuedAt(Instant.now().getEpochSecond())
                        .setNonce(UUID.randomUUID().toString());
        R<MesInspectionRevisionExecutionReceiptDTO> response;
        try {
            command.setSignature(command.sign(deliverySecret));
            response = mes.executeInspectionRevision(command);
        } catch (Exception ex) {
            fail(run, "现场响应未知，将按原单恢复");
            return false;
        }
        try {
            require(response != null && response.getCode() == 0, "MES未确认正式操作");
            QmsInspectionRevisionRunCodec.receipt(e, run.getPhase(), o, seal, response.getData());
            if ("ABORT".equals(run.getPhase())) {
                var c = coordination.cancel(id, tenant, factory);
                QmsInspectionRevisionCoordinationCodec.receipt(
                        e, c, "CANCEL", response.getData().getCancellation());
                for (var old : coordination.receipts(id, tenant, factory))
                    if ("RESERVE".equals(old.getOperation()))
                        QmsInspectionRevisionCoordinationCodec.continuity(
                                QmsInspectionRevisionCoordinationCodec.original(e, c, old),
                                response.getData().getCancellation());
            }
        } catch (RuntimeException ex) {
            fail(run, "现场回执未通过核验，结果未知，请按原单恢复");
            return false;
        }
        Date now = new Date();
        String op = run.getPhase(), raw = write(response.getData());
        require(
                runs.receipt(
                                new QmsInspectionRevisionRunReceiptEntity()
                                        .setId(IdWorker.getId())
                                        .setRequestId(id)
                                        .setTenantId(tenant)
                                        .setFactoryCode(factory)
                                        .setTaskId(e.getTaskId())
                                        .setOperation(op)
                                        .setPayload(raw)
                                        .setFingerprint(hash(raw))
                                        .setReceivedAt(now))
                        == 1,
                "现场已确认但本地回执未保存，须同单恢复");
        if ("ABORT".equals(op)) {
            String canceled = write(response.getData().getCancellation());
            require(
                    coordination.insertReceipt(
                                    new QmsInspectionRevisionReceiptEntity()
                                            .setId(IdWorker.getId())
                                            .setRequestId(id)
                                            .setTenantId(tenant)
                                            .setFactoryCode(factory)
                                            .setTaskId(e.getTaskId())
                                            .setOperation("CANCEL")
                                            .setPayload(canceled)
                                            .setFingerprint(hash(canceled))
                                            .setRecordedAt(now))
                            == 1,
                    "永久中止取消回执未保存");
            d.setStatus("CANCELED")
                    .setAttemptCount(d.getAttemptCount() + 1)
                    .setNextAttemptAt(null)
                    .setUpdatedAt(now)
                    .setLastError(null);
            require(coordination.progress(d) == 1, "取消终态进度未保存");
        }
        if ("SEAL".equals(op)) QmsInspectionRevisionRunService.ready(run, "COMMIT");
        else
            run.setPhase("APPLY".equals(op) ? "DONE" : "STOPPED")
                    .setStatus("SUCCEEDED")
                    .setAttemptCount(run.getAttemptCount() + 1)
                    .setNextAttemptAt(null)
                    .setUpdatedAt(now)
                    .setLastError(null);
        require(runs.progress(run) == 1, "正式执行阶段未保存");
        return true;
    }

    @Transactional(
            rollbackFor = Exception.class,
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public void localFailure(Long id) {
        QmsInspectionEventWorker.scope();
        coordination.delivery(
                id, TenantContextHolder.getTenantId(), FactoryContextHolder.getFactoryCode(), true);
        var r =
                runs.run(
                        id,
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode(),
                        true);
        if (r != null
                && "COMMIT".equals(r.getPhase())
                && runs.outcome(id, r.getTenantId(), r.getFactoryCode()) == null) {
            r.setStatus("ATTENTION")
                    .setNextAttemptAt(null)
                    .setUpdatedAt(new Date())
                    .setLastError("本地改判未完成，请核对后恢复或中止");
            require(runs.progress(r) == 1, "本地提交失败进度未保存");
        }
    }

    private void fail(QmsInspectionRevisionRunEntity r, String message) {
        int n = r.getAttemptCount() + 1;
        boolean retry = n < 8;
        r.setAttemptCount(n)
                .setStatus(retry ? "RETRY_WAIT" : "ATTENTION")
                .setNextAttemptAt(
                        retry
                                ? new Date(
                                        System.currentTimeMillis()
                                                + Math.min(3600, 30L * (1L << Math.min(n - 1, 7)))
                                                        * 1000)
                                : null)
                .setUpdatedAt(new Date())
                .setLastError(message);
        require(runs.progress(r) == 1, "正式执行未知结果未保存");
    }
}
