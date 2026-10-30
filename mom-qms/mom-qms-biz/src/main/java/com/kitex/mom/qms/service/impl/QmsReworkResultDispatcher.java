package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsReworkResultCodec.require;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.qms.mapper.QmsReworkResultOutboxMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;

/** 提交后快速投递及独立后台补偿，共用同一受控工作器。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QmsReworkResultDispatcher {
    private final QmsReworkResultOutboxMapper outbox;
    private final QmsReworkResultWorker worker;

    public int scan() {
        require(
                RequestContextHolder.getRequestAttributes() == null
                        && SecurityContextHolder.getContext().getAuthentication() == null
                        && !TransactionSynchronizationManager.isActualTransactionActive(),
                "复检跨租户扫描仅允许独立后台调度");
        require(java.util.Objects.equals(outbox.schemaVersion(), 1), "复检发送队列结构未就绪");
        int delivered = 0;
        for (var row : outbox.due())
            if (dispatch(row.getTenantId(), row.getFactoryCode(), row.getId())) delivered++;
        return delivered;
    }

    boolean dispatch(Long tenant, String factory, Long id) {
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
            return worker.deliver(id);
        } catch (RuntimeException e) {
            log.warn("复检发送未确认，保留队列，id={}，异常类型={}", id, e.getClass().getSimpleName());
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
