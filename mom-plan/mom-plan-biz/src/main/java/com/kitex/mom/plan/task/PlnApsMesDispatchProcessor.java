package com.kitex.mom.plan.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.plan.entity.PlnApsMesDispatchOutboxEntity;
import com.kitex.mom.plan.mapper.PlnApsMesDispatchOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

/**
 * APS 初始排程下达 MES 的跨租户批处理器。
 *
 * <p>扫描阶段只定位到期 Outbox，实际处理时逐条进入其真实租户和工厂上下文。每条记录由工作器独立
 * 领取并持有短租约，因此多实例并发执行同一 XXL-Job 也不会重复发送成功单元。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlnApsMesDispatchProcessor {

    private final PlnApsMesDispatchOutboxMapper outboxMapper;
    private final PlnApsMesDispatchWorker worker;

    /**
     * 处理一批到期任务，单条失败不阻断其他工厂。
     */
    public int processDue(int limit) {
        int batchSize = Math.max(1, Math.min(limit, 200));
        LocalDateTime now = LocalDateTime.now();
        List<PlnApsMesDispatchOutboxEntity> due = outboxMapper.selectPage(new Page<>(1, batchSize),
                new LambdaQueryWrapper<PlnApsMesDispatchOutboxEntity>()
                        .in(PlnApsMesDispatchOutboxEntity::getStatus,
                                List.of("PENDING", "PARTIAL", "RETRY_WAIT"))
                        .and(query -> query.isNull(PlnApsMesDispatchOutboxEntity::getNextRetryTime)
                                .or().le(PlnApsMesDispatchOutboxEntity::getNextRetryTime, now))
                        .and(query -> query.isNull(PlnApsMesDispatchOutboxEntity::getLeaseUntil)
                                .or().le(PlnApsMesDispatchOutboxEntity::getLeaseUntil, now))
                        .orderByAsc(PlnApsMesDispatchOutboxEntity::getNextRetryTime)
                        .orderByAsc(PlnApsMesDispatchOutboxEntity::getQueuedTime)
                        .orderByAsc(PlnApsMesDispatchOutboxEntity::getId)).getRecords();
        int processed = 0;
        for (PlnApsMesDispatchOutboxEntity candidate : due) {
            try {
                Boolean result = TenantBroker.applyAs(candidate.getTenantId(), ignored ->
                        inFactory(candidate.getFactoryCode(), () -> worker.processScheduled(candidate.getId())));
                if (Boolean.TRUE.equals(result)) processed++;
            } catch (RuntimeException ex) {
                log.warn("APS到MES异步下达本轮处理失败，outboxId={}，tenantId={}，factoryCode={}，原因={}",
                        candidate.getId(), candidate.getTenantId(), candidate.getFactoryCode(), ex.getMessage());
            }
        }
        return processed;
    }

    private <T> T inFactory(String factoryCode, Supplier<T> work) {
        String previousFactory = FactoryContextHolder.getFactoryCode();
        boolean previousSkip = Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
        try {
            FactoryContextHolder.clear();
            FactoryContextHolder.setFactoryCode(factoryCode);
            return work.get();
        } finally {
            FactoryContextHolder.clear();
            FactoryContextHolder.setFactoryCode(previousFactory);
            if (previousSkip) FactoryContextHolder.setFactorySkip();
        }
    }
}
