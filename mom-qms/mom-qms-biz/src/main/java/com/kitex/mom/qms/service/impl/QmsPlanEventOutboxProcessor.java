package com.kitex.mom.qms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.qms.entity.QmsPlanEventOutboxEntity;
import com.kitex.mom.qms.entity.QmsPlanEventTargetEntity;
import com.kitex.mom.qms.mapper.QmsPlanEventOutboxMapper;
import com.kitex.mom.qms.mapper.QmsPlanEventTargetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * QMS 质量事件 Outbox 的批量调度入口。
 *
 * <p>Outbox 写入服务只负责在质量业务事务中可靠保存事件；本处理器由定时任务单向调用 Worker，
 * 负责来源准备、逐目标投递和汇总。拆分后质量任务服务不会通过 Outbox 写入服务反向依赖返工服务，
 * 从结构上消除“质检任务 → Outbox → Worker → 返工 → 质检任务”的 Bean 循环。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QmsPlanEventOutboxProcessor {

    private final QmsPlanEventOutboxMapper outboxMapper;
    private final QmsPlanEventTargetMapper targetMapper;
    private final QmsPlanEventOutboxWorker worker;
    private final QmsPlanEventOutboxFailureService failureService;

    /**
     * 处理一批到期质量事件。
     *
     * <p>每个事件在自己的租户上下文中处理，单条失败只记录失败证据，不阻断同批其他事件。</p>
     *
     * @param limit 本批最大事件数，实际限制在 1 至 200
     * @return 已完成全部 PLAN 目标投递的事件数
     */
    public int dispatchDue(int limit) {
        int batchSize = Math.max(1, Math.min(limit, 200));
        List<QmsPlanEventOutboxEntity> due = outboxMapper.selectPage(new Page<>(1, batchSize),
                        new LambdaQueryWrapper<QmsPlanEventOutboxEntity>()
                                .in(QmsPlanEventOutboxEntity::getStatus, "PENDING", "RETRY_WAIT", "READY")
                                .le(QmsPlanEventOutboxEntity::getNextAttemptTime, LocalDateTime.now())
                                .orderByAsc(QmsPlanEventOutboxEntity::getNextAttemptTime)
                                .orderByAsc(QmsPlanEventOutboxEntity::getId))
                .getRecords();
        int delivered = 0;
        for (QmsPlanEventOutboxEntity candidate : due) {
            try {
                boolean done = candidate.getTenantId() == null
                        ? dispatchOne(candidate.getId())
                        : TenantBroker.applyAs(candidate.getTenantId(),
                        ignored -> dispatchOne(candidate.getId()));
                if (done) {
                    delivered++;
                }
            } catch (RuntimeException exception) {
                log.error("QMS质量事件调度异常，eventId={}，tenantId={}",
                        candidate.getEventId(), candidate.getTenantId(), exception);
            }
        }
        return delivered;
    }

    private boolean dispatchOne(Long outboxId) {
        QmsPlanEventOutboxEntity current = outboxMapper.selectById(outboxId);
        if (current == null) {
            return false;
        }
        if (List.of("PENDING", "RETRY_WAIT").contains(current.getStatus())) {
            try {
                worker.prepareOne(outboxId);
            } catch (RuntimeException exception) {
                boolean retryable = !(exception
                        instanceof QmsPlanEventOutboxWorker.NonRetryableDeliveryException);
                failureService.markPrepareFailure(outboxId, exception, retryable);
                return false;
            }
        }

        current = outboxMapper.selectById(outboxId);
        if (current == null || !"READY".equals(current.getStatus())) {
            return false;
        }
        List<QmsPlanEventTargetEntity> targets = targetMapper.selectList(
                new LambdaQueryWrapper<QmsPlanEventTargetEntity>()
                        .eq(QmsPlanEventTargetEntity::getOutboxId, outboxId)
                        .ne(QmsPlanEventTargetEntity::getStatus, "DELIVERED")
                        .orderByAsc(QmsPlanEventTargetEntity::getIssueNo)
                        .orderByAsc(QmsPlanEventTargetEntity::getId));
        for (QmsPlanEventTargetEntity target : targets) {
            try {
                worker.deliverTarget(target.getId());
            } catch (RuntimeException exception) {
                failureService.markTargetFailure(target.getId(), exception);
            }
        }
        return Boolean.TRUE.equals(worker.finalizeOne(outboxId));
    }
}
