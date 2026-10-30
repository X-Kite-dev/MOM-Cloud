package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionEventDeliveryCodec.require;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.qms.mapper.QmsInspectionEventOutboxMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Objects;

/** 提交后快速投递及独立后台补偿，共用同一受控工作器。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QmsInspectionEventDispatcher {
    private final QmsInspectionEventOutboxMapper outbox;
    private final QmsInspectionEventWorker worker;

    public int scan() {
        require(
                RequestContextHolder.getRequestAttributes() == null
                        && SecurityContextHolder.getContext().getAuthentication() == null
                        && !TransactionSynchronizationManager.isActualTransactionActive(),
                "普通检验事件跨租户扫描仅允许独立后台调度");
        require(Objects.equals(outbox.schemaVersion(), 1), "普通检验事件发送队列结构未就绪");
        int delivered = 0;
        for (var row : outbox.due())
            if (dispatch(row.getTenantId(), row.getFactoryCode(), row.getTaskId())) delivered++;
        return delivered;
    }

    boolean dispatch(Long tenant, String factory, Long taskId) {
        Long oldTenant = TenantContextHolder.getTenantId();
        boolean oldSkip = TenantContextHolder.getTenantSkip();
        String oldFactory = FactoryContextHolder.getFactoryCode();
        boolean oldFactorySkip = Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
        try {
            require(
                    tenant != null && tenant > 0 && factory != null && !factory.isBlank(),
                    "发送候选缺少组织");
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
            TenantContextHolder.setTenantId(tenant);
            FactoryContextHolder.setFactoryCode(factory);
            return worker.deliver(taskId);
        } catch (RuntimeException e) {
            log.warn("普通检验事件发送未确认，保留队列，taskId={}，异常类型={}", taskId, e.getClass().getSimpleName());
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
