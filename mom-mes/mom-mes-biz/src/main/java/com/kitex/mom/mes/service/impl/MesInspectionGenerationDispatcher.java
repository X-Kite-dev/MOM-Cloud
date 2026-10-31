package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import com.kitex.mom.mes.mapper.MesInspectionGenerationPendingMapper;
import com.kitex.mom.mes.service.MesQualityGateService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Immediate post-commit delivery plus durable, original-number recovery from committed source rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MesInspectionGenerationDispatcher {
    private final MesInspectionGenerationPendingMapper pending;
    private final MesQualityGateService quality;
    private final AtomicLong after = new AtomicLong();

    private record Intent(Long tenant, String factory, Long id) {}

    public void afterCommit(List<MesInspectionGenRecordEntity> records) {
        if (records.isEmpty()) return;
        require(
                TransactionSynchronizationManager.isActualTransactionActive()
                        && TransactionSynchronizationManager.isSynchronizationActive(),
                "首检投递必须随实际下达事务登记");
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        require(
                tenant != null
                        && tenant > 0
                        && factory != null
                        && !factory.isBlank()
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
                "首检投递缺实际组织");
        var intents = new ArrayList<Intent>();
        for (var r : records) {
            require(
                    r != null
                            && r.getId() != null
                            && r.getId() > 0
                            && Objects.equals(tenant, r.getTenantId())
                            && Objects.equals(factory, r.getFactoryCode()),
                    "首检待投递记录没有完整保存或归属不一致");
            intents.add(new Intent(tenant, factory, r.getId()));
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        for (var intent : intents) deliver(intent);
                    }
                });
    }

    public int scan() {
        require(
                RequestContextHolder.getRequestAttributes() == null
                        && SecurityContextHolder.getContext().getAuthentication() == null
                        && !TransactionSynchronizationManager.isActualTransactionActive(),
                "首检跨组织恢复仅允许独立后台调度");
        var candidates = pending.pendingAfter(after.get());
        if (candidates.isEmpty() && after.getAndSet(0) > 0) candidates = pending.pendingAfter(0);
        int done = 0;
        for (var r : candidates) {
            after.set(r.getId());
            if (deliver(new Intent(r.getTenantId(), r.getFactoryCode(), r.getId()))) done++;
        }
        return done;
    }

    private boolean deliver(Intent i) {
        Long oldTenant = TenantContextHolder.getTenantId();
        String oldFactory = FactoryContextHolder.getFactoryCode();
        boolean oldSkip = TenantContextHolder.getTenantSkip(),
                oldFactorySkip = Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
        try {
            require(
                    i.tenant() != null
                            && i.tenant() > 0
                            && i.factory() != null
                            && !i.factory().isBlank()
                            && i.id() != null
                            && i.id() > 0,
                    "首检恢复候选缺实际身份");
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
            TenantContextHolder.setTenantId(i.tenant());
            FactoryContextHolder.setFactoryCode(i.factory());
            Long inspection = quality.deliverPendingGeneration(i.id());
            return inspection != null && inspection > 0;
        } catch (RuntimeException failure) {
            log.warn(
                    "首检原号投递未确认，保留原件，recordId={}，异常类型={}",
                    i.id(),
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

    private static void require(boolean v, String message) {
        if (!v) throw new CheckedException(message);
    }
}
