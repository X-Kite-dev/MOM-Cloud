package com.kitex.mom.qms.service.impl;

import cn.hutool.core.util.StrUtil;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesReworkInspectionResultDTO;
import com.kitex.mom.qms.entity.QmsInspectionProcedureTaskEntity;
import com.kitex.mom.qms.entity.QmsInspectionProductTaskEntity;
import com.kitex.mom.qms.entity.QmsReworkOrderEntity;
import com.kitex.mom.qms.entity.QmsReworkResultOutboxEntity;
import com.kitex.mom.qms.enums.InspectionTaskResultEnum;
import com.kitex.mom.qms.mapper.QmsReworkOrderMapper;
import com.kitex.mom.qms.mapper.QmsReworkResultOutboxMapper;
import com.kitex.mom.qms.service.QmsReworkResultPublisher;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class QmsReworkResultPublisherImpl implements QmsReworkResultPublisher {
    private static final Set<String> SUPPORTED_RESULTS =
            Set.of(
                    InspectionTaskResultEnum.QUALIFIED.name(),
                    InspectionTaskResultEnum.UN_QUALIFIED.name(),
                    InspectionTaskResultEnum.RELEASE.name(),
                    InspectionTaskResultEnum.RECEIVE.name(),
                    InspectionTaskResultEnum.EXEMPTION.name());

    private final QmsReworkOrderMapper reworkOrderMapper;
    private final QmsReworkResultOutboxMapper outbox;
    private final QmsReworkResultDispatcher dispatcher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(String inspectionType, Long qmsTaskId) {
        if (qmsTaskId == null || StrUtil.isBlank(inspectionType)) return;
        QmsReworkOrderEntity order =
                reworkOrderMapper.selectOne(
                        scopedOrders()
                                .eq(
                                        QmsReworkOrderEntity::getInspectionType,
                                        inspectionType.trim().toUpperCase(Locale.ROOT))
                                .eq(QmsReworkOrderEntity::getReinspectionTaskId, qmsTaskId)
                                .last("FOR UPDATE"));
        if (order == null) return;
        publish(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retry(Long reworkOrderId) {
        QmsReworkOrderEntity order =
                reworkOrderMapper.selectOne(
                        scopedOrders()
                                .eq(QmsReworkOrderEntity::getId, reworkOrderId)
                                .last("FOR UPDATE"));
        if (order == null || order.getReinspectionTaskId() == null) {
            throw new IllegalStateException("返工协调单不存在或尚未生成复检任务");
        }
        var pending =
                outbox.selectOne(
                        QmsReworkResultWorker.scoped()
                                .eq(QmsReworkResultOutboxEntity::getOrderId, order.getId())
                                .ne(QmsReworkResultOutboxEntity::getStatus, "DELIVERED")
                                .orderByAsc(QmsReworkResultOutboxEntity::getSequenceNo)
                                .last("LIMIT 1 FOR UPDATE"));
        if (pending != null) {
            installed();
            QmsReworkResultCodec.decode(pending, order);
            int saved =
                    outbox.update(
                            null,
                            new LambdaUpdateWrapper<QmsReworkResultOutboxEntity>()
                                    .eq(QmsReworkResultOutboxEntity::getId, pending.getId())
                                    .eq(
                                            QmsReworkResultOutboxEntity::getTenantId,
                                            order.getTenantId())
                                    .eq(
                                            QmsReworkResultOutboxEntity::getFactoryCode,
                                            order.getFactoryCode())
                                    .set(QmsReworkResultOutboxEntity::getStatus, "RETRY_WAIT")
                                    .set(
                                            QmsReworkResultOutboxEntity::getNextAttemptTime,
                                            LocalDateTime.now()));
            QmsReworkResultCodec.require(saved == 1, "复检原件重新排队失败");
            afterCommit(pending);
            return;
        }
        publish(order);
    }

    private void publish(QmsReworkOrderEntity order) {
        MesReworkInspectionResultDTO result;
        try {
            Snapshot task = snapshot(order);
            if (StrUtil.isBlank(task.result())) return;
            boolean qualified = InspectionTaskResultEnum.QUALIFIED.name().equals(task.result());
            if (!SUPPORTED_RESULTS.contains(task.result())) {
                throw new IllegalStateException("返工复检结论不支持数量同步");
            }
            int total = Objects.requireNonNullElse(task.total(), 0);
            int usable = Objects.requireNonNullElse(task.usable(), 0);
            int handled = Objects.requireNonNullElse(task.handled(), 0);
            if (total <= 0 || usable < 0 || handled < 0 || usable > handled || handled > total) {
                throw new IllegalStateException("返工复检总数、可用数或已处置数关系不正确");
            }
            if (qualified) {
                if ((usable != 0 || handled != 0) && (usable != total || handled != total)) {
                    throw new IllegalStateException("返工复检合格结论与显式处置数量不一致");
                }
                usable = total;
                handled = total;
            }
            result =
                    new MesReworkInspectionResultDTO()
                            .setRequestId(order.getRequestId())
                            .setQmsTaskId(order.getReinspectionTaskId())
                            .setInspectionType(order.getInspectionType())
                            .setResult(task.result())
                            .setTotalNum(total)
                            .setUsableNum(usable)
                            .setHandleNum(handled)
                            .setHandleResult(task.handleResult());
        } catch (IllegalStateException exception) {
            var failed =
                    new QmsReworkOrderEntity()
                            .setStatus("RESULT_SYNC_FAILED")
                            .setReinspectionStatus("SYNC_FAILED")
                            .setResultSyncCount(
                                    Objects.requireNonNullElse(order.getResultSyncCount(), 0) + 1);
            requireReceipt(order, failed, StrUtil.maxLength(exception.getMessage(), 1000));
            return;
        }
        installed();
        QmsReworkResultCodec.require(
                TransactionSynchronizationManager.isActualTransactionActive(), "复检待发送原件必须加入业务事务");
        var latest =
                outbox.selectOne(
                        QmsReworkResultWorker.scoped()
                                .eq(QmsReworkResultOutboxEntity::getOrderId, order.getId())
                                .orderByDesc(QmsReworkResultOutboxEntity::getSequenceNo)
                                .last("LIMIT 1"));
        if (latest != null && result.equals(QmsReworkResultCodec.decode(latest, order))) {
            if (!"DELIVERED".equals(latest.getStatus())) afterCommit(latest);
            return;
        }
        long sequence = latest == null ? 1 : Math.addExact(latest.getSequenceNo(), 1);
        QmsReworkResultCodec.require(sequence <= 5000, "单协调单复检结果超过5000件，请先治理");
        var row =
                new QmsReworkResultOutboxEntity()
                        .setId(IdWorker.getId())
                        .setTenantId(order.getTenantId())
                        .setFactoryCode(order.getFactoryCode())
                        .setOrderId(order.getId())
                        .setSequenceNo(sequence)
                        .setStatus("PENDING")
                        .setAttemptCount(0)
                        .setCreatedAt(LocalDateTime.now())
                        .setNextAttemptTime(LocalDateTime.now());
        row.setPayload(QmsReworkResultCodec.encode(row, result))
                .setFingerprint(QmsReworkResultCodec.hash(row.getPayload()));
        QmsReworkResultCodec.decode(row, order);
        QmsReworkResultCodec.require(outbox.insert(row) == 1, "复检待发送原件保存失败");
        var blocking =
                outbox.selectOne(
                        QmsReworkResultWorker.scoped()
                                .eq(QmsReworkResultOutboxEntity::getOrderId, order.getId())
                                .ne(QmsReworkResultOutboxEntity::getStatus, "DELIVERED")
                                .orderByAsc(QmsReworkResultOutboxEntity::getSequenceNo)
                                .last("LIMIT 1"));
        boolean failed =
                blocking != null
                        && Set.of("RETRY_WAIT", "ATTENTION").contains(blocking.getStatus());
        requireReceipt(
                order,
                new QmsReworkOrderEntity()
                        .setStatus(failed ? "RESULT_SYNC_FAILED" : "RESULT_SYNC_PENDING")
                        .setReinspectionStatus(failed ? "SYNC_FAILED" : "SYNC_PENDING"),
                failed ? blocking.getLastError() : null);
        afterCommit(row);
    }

    private void afterCommit(QmsReworkResultOutboxEntity row) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        dispatcher.dispatch(row.getTenantId(), row.getFactoryCode(), row.getId());
                    }
                });
    }

    private void installed() {
        QmsReworkResultCodec.require(Objects.equals(outbox.schemaVersion(), 1), "复检发送队列结构未就绪");
    }

    private void requireReceipt(
            QmsReworkOrderEntity order, QmsReworkOrderEntity receipt, String error) {
        int updated =
                reworkOrderMapper.update(
                        receipt,
                        new LambdaUpdateWrapper<QmsReworkOrderEntity>()
                                .eq(QmsReworkOrderEntity::getId, order.getId())
                                .eq(QmsReworkOrderEntity::getTenantId, order.getTenantId())
                                .eq(QmsReworkOrderEntity::getFactoryCode, order.getFactoryCode())
                                .set(QmsReworkOrderEntity::getLastError, error));
        QmsReworkResultCodec.require(updated == 1, "返工复检同步回执保存失败，请按原协调单重试");
    }

    private LambdaQueryWrapper<QmsReworkOrderEntity> scopedOrders() {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        if (tenant == null
                || tenant <= 0
                || TenantContextHolder.getTenantSkip()
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())
                || StrUtil.isBlank(factory)) {
            throw new IllegalStateException("返工复检同步缺少实际租户或工厂");
        }
        return new LambdaQueryWrapper<QmsReworkOrderEntity>()
                .eq(QmsReworkOrderEntity::getTenantId, tenant)
                .eq(QmsReworkOrderEntity::getFactoryCode, factory);
    }

    private Snapshot snapshot(QmsReworkOrderEntity order) {
        if ("PROCEDURE".equals(order.getInspectionType())) {
            QmsInspectionProcedureTaskEntity task =
                    outbox.procedure(
                            order.getReinspectionTaskId(),
                            order.getTenantId(),
                            order.getFactoryCode());
            if (task == null) throw new IllegalStateException("返工工序复检任务不存在");
            requireSource(order, task.getSourceRequestId(), task.getOrderNo());
            return new Snapshot(
                    task.getResult(),
                    task.getNum(),
                    task.getUsableNum(),
                    task.getHandleNum(),
                    task.getHandleResult());
        }
        if ("PRODUCT".equals(order.getInspectionType())) {
            QmsInspectionProductTaskEntity task =
                    outbox.product(
                            order.getReinspectionTaskId(),
                            order.getTenantId(),
                            order.getFactoryCode());
            if (task == null) throw new IllegalStateException("返工成品复检任务不存在");
            requireSource(order, task.getSourceRequestId(), task.getOrderNo());
            return new Snapshot(
                    task.getResult(),
                    task.getNum(),
                    task.getUsableNum(),
                    task.getHandleNum(),
                    task.getHandleResult());
        }
        throw new IllegalStateException("不支持的返工复检类型");
    }

    private void requireSource(
            QmsReworkOrderEntity order, String sourceRequestId, String mesTaskCode) {
        if (StrUtil.isBlank(order.getRequestId())
                || StrUtil.isBlank(order.getMesTaskCode())
                || !Objects.equals("REWORK-REINSPECTION:" + order.getRequestId(), sourceRequestId)
                || !Objects.equals(order.getMesTaskCode(), mesTaskCode)) {
            throw new IllegalStateException("返工复检原件与协调单来源不一致");
        }
    }

    private record Snapshot(
            String result, Integer total, Integer usable, Integer handled, String handleResult) {}
}
