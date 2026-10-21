package com.kitex.mom.plan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.plan.entity.PlnAutomationEventEntity;
import com.kitex.mom.plan.mapper.PlnAutomationEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 制造自动化事件的批量执行入口。
 *
 * <p>事件收件与事件执行必须保持两个独立依赖方向：业务服务只依赖收件服务，
 * 定时任务才依赖本处理器和业务 Worker，避免 Worker 回调计划业务时形成 Bean 环。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlnAutomationEventProcessor {

    private final PlnAutomationEventMapper eventMapper;
    private final PlnAutomationEventWorker worker;
    private final PlnAutomationEventFailureService failureService;

    /**
     * 领取并处理一批到期事件。
     *
     * <p>每个事件在自身租户上下文和独立事务中执行，单个事件失败不会阻断批次。业务校验失败和明确的
     * 不可重试异常直接进入终态，其余异常由失败服务计算下次重试时刻。</p>
     *
     * @param limit 本批最大领取数量，实际限制在 1 至 200
     * @return 本批成功完成的事件数量
     */
    public int processDueEvents(int limit) {
        int batchSize = Math.max(1, Math.min(limit, 200));
        List<PlnAutomationEventEntity> due = eventMapper.selectPage(new Page<>(1, batchSize),
                new LambdaQueryWrapper<PlnAutomationEventEntity>()
                        .in(PlnAutomationEventEntity::getStatus, "RECEIVED", "RETRY_WAIT")
                        .le(PlnAutomationEventEntity::getNextAttemptTime, LocalDateTime.now())
                        .orderByAsc(PlnAutomationEventEntity::getPriority)
                        .orderByAsc(PlnAutomationEventEntity::getNextAttemptTime)).getRecords();
        int succeeded = 0;
        for (PlnAutomationEventEntity candidate : due) {
            try {
                Boolean processed = candidate.getTenantId() == null
                        ? worker.processOne(candidate.getId())
                        : TenantBroker.applyAs(candidate.getTenantId(), ignored -> worker.processOne(candidate.getId()));
                if (Boolean.TRUE.equals(processed)) {
                    succeeded++;
                }
            } catch (RuntimeException ex) {
                boolean retryable = !(ex instanceof CheckedException
                        || ex instanceof PlnAutomationEventWorker.NonRetryableEventException);
                try {
                    if (candidate.getTenantId() == null) {
                        failureService.markFailure(candidate.getId(), ex, retryable);
                    } else {
                        TenantBroker.applyAs(candidate.getTenantId(),
                                ignored -> failureService.markFailure(candidate.getId(), ex, retryable));
                    }
                } catch (RuntimeException failureEx) {
                    log.error("制造异常事件失败状态保存异常，eventNo={}，tenantId={}",
                            candidate.getEventNo(), candidate.getTenantId(), failureEx);
                }
            }
        }
        return succeeded;
    }
}
