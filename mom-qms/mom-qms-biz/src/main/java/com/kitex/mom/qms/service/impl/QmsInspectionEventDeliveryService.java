package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionEventDeliveryCodec.require;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.qms.entity.QmsInspectionEventEntity;
import com.kitex.mom.qms.entity.QmsInspectionEventOutboxEntity;
import com.kitex.mom.qms.mapper.QmsInspectionEventOutboxMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** 用户只可读本工厂进度/将最早未确认原件重新排队，不得构造事件或越过前件。 */
@Service
@RequiredArgsConstructor
public class QmsInspectionEventDeliveryService {
    private final QmsInspectionEventService events;
    private final QmsInspectionHistorySupport access;
    private final QmsInspectionEventPublisher publisher;
    private final QmsInspectionEventOutboxMapper outbox;

    @Transactional(rollbackFor = Exception.class)
    public boolean retry(Long taskId) {
        access.requireWrite();
        installed();
        require(
                taskId != null
                        && taskId > 0
                        && outbox.sourceTask(
                                        taskId,
                                        TenantContextHolder.getTenantId(),
                                        FactoryContextHolder.getFactoryCode(),
                                        true)
                                != null,
                "当前组织不存在实际检验任务");
        var originals = events.originals("PROCEDURE", taskId);
        require(publisher.enqueue(originals), "此任务不是可恢复的普通MES工序检验原件");
        QmsInspectionEventOutboxEntity anchor;
        try {
            anchor =
                    outbox.anchorForRetry(
                            taskId,
                            TenantContextHolder.getTenantId(),
                            FactoryContextHolder.getFactoryCode());
        } catch (PessimisticLockingFailureException | QueryTimeoutException busy) {
            // 抛出以回滚整个重排事务并释放已取得的QMS任务锁，不能吞掉PG已中止事务。
            throw new CheckedException("普通检验原件正在投递，请稍后重试", busy);
        }
        require(anchor != null, "普通检验待发送链首缺失");
        var head =
                outbox.head(
                        taskId,
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode());
        if (head == null) return false;
        QmsInspectionEventDeliveryCodec.verify(
                head, outbox.original(head.getId(), head.getTenantId(), head.getFactoryCode()));
        String status = head.getAttemptCount() == 0 ? "PENDING" : "RETRY_WAIT";
        require(
                outbox.update(
                                null,
                                new LambdaUpdateWrapper<QmsInspectionEventOutboxEntity>()
                                        .eq(QmsInspectionEventOutboxEntity::getId, head.getId())
                                        .eq(
                                                QmsInspectionEventOutboxEntity::getTenantId,
                                                head.getTenantId())
                                        .eq(
                                                QmsInspectionEventOutboxEntity::getFactoryCode,
                                                head.getFactoryCode())
                                        .set(QmsInspectionEventOutboxEntity::getStatus, status)
                                        .set(
                                                QmsInspectionEventOutboxEntity::getNextAttemptTime,
                                                new Date()))
                        == 1,
                "普通检验原件重新排队失败");
        return true;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<QmsInspectionEventOutboxEntity> status(Long taskId) {
        access.requireRead();
        installed();
        require(
                taskId != null
                        && taskId > 0
                        && outbox.sourceTask(
                                        taskId,
                                        TenantContextHolder.getTenantId(),
                                        FactoryContextHolder.getFactoryCode(),
                                        false)
                                != null,
                "当前组织不存在实际检验任务");
        var originals = events.originals("PROCEDURE", taskId);
        var rows =
                outbox.stream(
                        taskId,
                        TenantContextHolder.getTenantId(),
                        FactoryContextHolder.getFactoryCode());
        require(rows.size() <= 5000, "普通检验发件超过5000件，请先治理");
        var indexed = new HashMap<Long, QmsInspectionEventEntity>();
        for (var e : originals) indexed.put(e.getId(), e);
        for (var row : rows) QmsInspectionEventDeliveryCodec.verify(row, indexed.get(row.getId()));
        return rows;
    }

    private void installed() {
        require(Objects.equals(1, outbox.schemaVersion()), "普通检验发件结构或守卫未就绪");
    }
}
