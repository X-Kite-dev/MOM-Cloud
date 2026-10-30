package com.kitex.mom.plan.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.mapper.PlnAutomationEventMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

/**
 * 制造自动化事件的批量执行入口。
 *
 * <p>事件收件与事件执行必须保持两个独立依赖方向：业务服务只依赖收件服务， 定时任务才依赖本处理器和业务 Worker，避免 Worker 回调计划业务时形成 Bean 环。
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
     * <p>每个事件在自身租户上下文和独立事务中执行，单个事件失败不会阻断批次。业务校验失败和明确的 不可重试异常直接进入终态，其余异常由失败服务计算下次重试时刻。
     *
     * @param limit 本批最大领取数量，实际限制在 1 至 200
     * @return 本批成功完成的事件数量
     */
    public int processDueEvents(int limit) {
        int succeeded = 0;
        for (var candidate : eventMapper.due(Math.max(1, Math.min(limit, 200)))) {
            if (candidate.getTenantId() == null
                    || candidate.getTenantId() <= 0
                    || candidate.getFactoryCode() == null
                    || candidate.getFactoryCode().isBlank()) {
                log.warn("制造事件缺实际组织，inboxId={}", candidate.getId());
                continue;
            }
            Long oldTenant = TenantContextHolder.getTenantId();
            boolean oldTenantSkip = TenantContextHolder.getTenantSkip();
            String oldFactory = FactoryContextHolder.getFactoryCode();
            boolean oldFactorySkip = Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
            try {
                TenantContextHolder.clear();
                FactoryContextHolder.clear();
                boolean result =
                        TenantBroker.applyAs(
                                candidate.getTenantId(),
                                ignored -> {
                                    FactoryContextHolder.setFactoryCode(candidate.getFactoryCode());
                                    try {
                                        return Boolean.TRUE.equals(
                                                worker.processOne(candidate.getId()));
                                    } catch (RuntimeException failure) {
                                        // 在TenantBroker包装前分类，业务拒绝不能被包装成可重试的网络失败。
                                        boolean retryable =
                                                !(failure instanceof CheckedException
                                                        || failure
                                                        instanceof
                                                        PlnAutomationEventWorker
                                                                .NonRetryableEventException);
                                        failureService.markFailure(
                                                candidate.getId(), failure, retryable);
                                        return false;
                                    }
                                });
                if (result) succeeded++;
            } catch (RuntimeException failure) {
                log.warn(
                        "制造事件执行或失败账未完成，inboxId={}，errorType={}",
                        candidate.getId(),
                        failure.getClass().getSimpleName());
            } finally {
                TenantContextHolder.clear();
                FactoryContextHolder.clear();
                TenantContextHolder.setTenantId(oldTenant);
                FactoryContextHolder.setFactoryCode(oldFactory);
                if (oldTenantSkip) TenantContextHolder.setTenantSkip();
                if (oldFactorySkip) FactoryContextHolder.setFactorySkip();
            }
        }
        return succeeded;
    }
}
