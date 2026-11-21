package com.kitex.mom.plan.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.entity.PlnApsMesDispatchOutboxEntity;
import com.kitex.mom.plan.mapper.PlnApsMesDispatchOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

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
        List<PlnApsMesDispatchOutboxEntity> due = outboxMapper.selectPage(new Page<>(1, batchSize, false),
                new LambdaQueryWrapper<PlnApsMesDispatchOutboxEntity>()
                        .and(eligible -> eligible.nested(waiting -> waiting
                                        .in(PlnApsMesDispatchOutboxEntity::getStatus, List.of("PENDING", "PARTIAL", "RETRY_WAIT"))
                                        .and(dueTime -> dueTime.isNull(PlnApsMesDispatchOutboxEntity::getNextRetryTime)
                                                .or().le(PlnApsMesDispatchOutboxEntity::getNextRetryTime, now)))
                                .or().eq(PlnApsMesDispatchOutboxEntity::getStatus, "PROCESSING"))
                        .and(query -> query.isNull(PlnApsMesDispatchOutboxEntity::getLeaseUntil)
                                .or().le(PlnApsMesDispatchOutboxEntity::getLeaseUntil, now))
                        .orderByAsc(PlnApsMesDispatchOutboxEntity::getNextRetryTime)
                        .orderByAsc(PlnApsMesDispatchOutboxEntity::getQueuedTime)
                        .orderByAsc(PlnApsMesDispatchOutboxEntity::getId)).getRecords();
        int processed = 0;
        for (PlnApsMesDispatchOutboxEntity candidate : due) {
            try {
                if (processInOrganization(candidate)) {
                    processed++;
                }
            } catch (RuntimeException ex) {
                log.warn("APS到MES异步下达本轮处理失败，outboxId={}，tenantId={}，factoryCode={}，原因={}",
                        candidate.getId(), candidate.getTenantId(), candidate.getFactoryCode(), ex.getMessage());
            }
        }
        return processed;
    }

    /** 扫描可以跨组织，执行只能进入原单的租户和工厂；线程复用时恢复原过滤状态。 */
    private boolean processInOrganization(PlnApsMesDispatchOutboxEntity candidate) {
        Long previousTenant = TenantContextHolder.getTenantId();
        boolean previousTenantSkip = TenantContextHolder.getTenantSkip();
        String previousFactory = FactoryContextHolder.getFactoryCode();
        boolean previousFactorySkip = Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
        try {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
            TenantContextHolder.setTenantId(candidate.getTenantId());
            FactoryContextHolder.setFactoryCode(candidate.getFactoryCode());
            return worker.processScheduled(candidate.getId());
        } finally {
            TenantContextHolder.clear();
            TenantContextHolder.setTenantId(previousTenant);
            if (previousTenantSkip) {
                TenantContextHolder.setTenantSkip();
            }
            FactoryContextHolder.clear();
            FactoryContextHolder.setFactoryCode(previousFactory);
            if (previousFactorySkip) {
                FactoryContextHolder.setFactorySkip();
            }
        }
    }
}
