package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.mapper.MesInspectionEventStateMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 只从独立后台消费，每条恢复实际组织；收件和人员查询不触发数量事务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MesInspectionEventApplicationDispatcher {
    private final MesInspectionEventStateMapper states;
    private final MesInspectionEventApplicationWorker worker;
    private final MesSourceQuantityPlanOutbox plans;
    // 调度位置不代表业务进度；失败也轮转，重启从头重新扫且不跳过任何原件。
    private final java.util.concurrent.atomic.AtomicLong scanAfter =
            new java.util.concurrent.atomic.AtomicLong();

    public int scan() {
        if (RequestContextHolder.getRequestAttributes() != null
                || SecurityContextHolder.getContext().getAuthentication() != null
                || TransactionSynchronizationManager.isActualTransactionActive())
            throw new CheckedException("检验事件跨组织应用只允许独立后台调度");
        if (!java.util.Objects.equals(1, states.schemaVersion()))
            throw new CheckedException("检验事件应用结构未就绪");
        int applied = 0;
        var rows = states.dueAfter(scanAfter.get());
        if (rows.isEmpty() && scanAfter.getAndSet(0) > 0) rows = states.dueAfter(0);
        for (var row : rows) {
            scanAfter.set(row.getId());
            if (consume(row.getTenantId(), row.getFactoryCode(), row.getId())) applied++;
        }
        for (var row : plans.candidates())
            deliverPlan(row.getTenantId(), row.getFactoryCode(), row.getId(), false);
        for (var row : plans.reworkCandidates())
            deliverPlan(row.getTenantId(), row.getFactoryCode(), row.getId(), true);
        return applied;
    }

    private void deliverPlan(Long tenant, String factory, Long id, boolean rework) {
        Long oldTenant = TenantContextHolder.getTenantId();
        String oldFactory = FactoryContextHolder.getFactoryCode();
        boolean oldSkip = TenantContextHolder.getTenantSkip(),
                oldFactorySkip = Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
        try {
            if (tenant == null || tenant <= 0 || factory == null || factory.isBlank())
                throw new CheckedException("PLAN投递候选缺实际组织");
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
            TenantContextHolder.setTenantId(tenant);
            FactoryContextHolder.setFactoryCode(factory);
            if (rework) plans.deliverRework(id);
            else plans.deliver(id);
        } catch (RuntimeException failure) {
            log.warn("PLAN来源通知未提交，保留原件，id={}，异常类型={}", id, failure.getClass().getSimpleName());
        } finally {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
            TenantContextHolder.setTenantId(oldTenant);
            FactoryContextHolder.setFactoryCode(oldFactory);
            if (oldSkip) TenantContextHolder.setTenantSkip();
            if (oldFactorySkip) FactoryContextHolder.setFactorySkip();
        }
    }

    boolean consume(Long tenant, String factory, Long record) {
        Long oldTenant = TenantContextHolder.getTenantId();
        boolean oldSkip = TenantContextHolder.getTenantSkip();
        String oldFactory = FactoryContextHolder.getFactoryCode();
        boolean oldFactorySkip = Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
        try {
            if (tenant == null || tenant <= 0 || factory == null || factory.isBlank())
                throw new CheckedException("应用候选缺实际组织");
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
            TenantContextHolder.setTenantId(tenant);
            FactoryContextHolder.setFactoryCode(factory);
            return worker.consume(record);
        } catch (RuntimeException failure) {
            log.warn(
                    "检验事件应用未提交，保留原件，recordId={}，异常类型={}",
                    record,
                    failure.getClass().getSimpleName());
            return false;
        } finally {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
            TenantContextHolder.setTenantId(oldTenant);
            FactoryContextHolder.setFactoryCode(oldFactory);
            if (oldSkip) TenantContextHolder.setTenantSkip();
            if (oldFactorySkip) FactoryContextHolder.setFactorySkip();
        }
    }
}
