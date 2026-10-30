package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionEventDeliveryCodec.require;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.qms.entity.*;
import com.kitex.mom.qms.mapper.QmsInspectionEventOutboxMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 与原历史/事件共享事务，登记真实前链；不另造一份当前任务快照。 */
@Service
@RequiredArgsConstructor
public class QmsInspectionEventPublisher {
    private final QmsInspectionEventOutboxMapper outbox;

    boolean enqueue(List<QmsInspectionEventEntity> originals) {
        QmsInspectionEventWorker.scope();
        require(TransactionSynchronizationManager.isActualTransactionActive(), "普通检验待发件必须加入原业务事务");
        require(
                originals != null && !originals.isEmpty() && originals.size() <= 5000,
                "普通检验发件缺少有界真实前链");
        var first = originals.get(0);
        if (!QmsInspectionEventDeliveryCodec.eligible(first)) {
            require(
                    originals.stream().noneMatch(QmsInspectionEventDeliveryCodec::eligible),
                    "普通检验来源中途变化，不能跳过真实前件");
            return false;
        }
        require(Objects.equals(1, outbox.schemaVersion()), "普通检验发件结构或守卫未就绪");
        var existing =
                outbox.stream(first.getTaskId(), first.getTenantId(), first.getFactoryCode());
        require(existing.size() <= originals.size(), "普通检验发件存在多余或缺源记录");
        Map<Long, QmsInspectionEventOutboxEntity> indexed = new HashMap<>();
        for (var row : existing) require(indexed.put(row.getId(), row) == null, "普通检验发件身份重复");
        QmsInspectionEventEntity previous = null;
        long bytes = 0;
        for (var e : originals) {
            require(
                    Objects.equals(e.getTenantId(), TenantContextHolder.getTenantId())
                            && Objects.equals(
                                    e.getFactoryCode(), FactoryContextHolder.getFactoryCode())
                            && Objects.equals(e.getTaskId(), first.getTaskId())
                            && QmsInspectionEventDeliveryCodec.eligible(e)
                            && e.getSequenceNo()
                                    == (previous == null ? 1 : previous.getSequenceNo() + 1)
                            && Objects.equals(
                                    e.getPreviousEventId(),
                                    previous == null ? null : previous.getId())
                            && Objects.equals(
                                    e.getPreviousFingerprint(),
                                    previous == null ? null : previous.getFingerprint()),
                    "普通检验发件前链/组织不连续");
            bytes = Math.addExact(bytes, e.getPayload().getBytes(StandardCharsets.UTF_8).length);
            require(bytes <= 8388608L, "普通检验发件前链超过8MiB");
            var stored = indexed.remove(e.getId());
            if (stored != null) {
                QmsInspectionEventDeliveryCodec.verify(stored, e);
            } else {
                var row =
                        new QmsInspectionEventOutboxEntity()
                                .setId(e.getId())
                                .setTenantId(e.getTenantId())
                                .setFactoryCode(e.getFactoryCode())
                                .setTaskId(e.getTaskId())
                                .setSequenceNo(e.getSequenceNo())
                                .setFingerprint(e.getFingerprint())
                                .setStatus("PENDING")
                                .setAttemptCount(0)
                                .setCreatedAt(new Date())
                                .setNextAttemptTime(new Date());
                require(outbox.insert(row) == 1, "普通检验待发件保存失败，原事实事务已回滚");
            }
            previous = e;
        }
        require(indexed.isEmpty(), "普通检验发件包含未匹配原件");
        return true;
    }
}
