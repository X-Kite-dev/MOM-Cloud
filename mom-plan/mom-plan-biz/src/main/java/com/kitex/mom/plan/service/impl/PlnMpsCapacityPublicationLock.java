package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.mapper.PlnMpsPolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 所有正式粗能力责任写路径的共同事务锁。
 *
 * <p>有永久请求号时先取请求锁，随后取本锁，最后才取版本/需求/原桶/案件头行锁。
 * 旧执行器保留单事务及下游原号，远端等待期间保守串行，禁止局部改成无锁窗口。</p>
 */
@Component
@RequiredArgsConstructor
public class PlnMpsCapacityPublicationLock {
    private final PlnMpsPolicyMapper mapper;

    /**
     * 仅能在实际租户的写事务内调用，锁持续到整个事务结束。
     */
    public void acquire(String factory) {
        if (TenantContextHolder.getTenantId() == null || TenantContextHolder.getTenantSkip()
                || StrUtil.isBlank(factory) || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new CheckedException("粗能力发布须实际租户/工厂写事务，禁止先取业务行锁或跳过隔离");
        }
        mapper.lockKey("CAPACITY_PUBLICATION_V1:" + TenantContextHolder.getTenantId() + ":" + factory);
    }
}

