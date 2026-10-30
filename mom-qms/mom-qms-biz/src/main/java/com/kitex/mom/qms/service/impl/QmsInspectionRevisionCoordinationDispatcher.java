package com.kitex.mom.qms.service.impl;

import static com.kitex.mom.qms.service.impl.QmsInspectionHistorySupport.require;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.qms.mapper.QmsInspectionRevisionCoordinationMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;

/** 独立后台跨域只读候选，逐件恢复真实scope，不伪造用户。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QmsInspectionRevisionCoordinationDispatcher {
    private final QmsInspectionRevisionCoordinationMapper mapper;
    private final QmsInspectionRevisionCoordinationWorker worker;

    public int scan() {
        require(
                RequestContextHolder.getRequestAttributes() == null
                        && SecurityContextHolder.getContext().getAuthentication() == null
                        && !TransactionSynchronizationManager.isActualTransactionActive(),
                "修订协调扫描只允许独立后台");
        require(Integer.valueOf(1).equals(mapper.ready()), "修订协调结构未就绪");
        int n = 0;
        for (var row : mapper.due()) {
            Long tenant = TenantContextHolder.getTenantId();
            String factory = FactoryContextHolder.getFactoryCode();
            boolean ts = TenantContextHolder.getTenantSkip(),
                    fs = Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
            try {
                require(
                        row.getTenantId() != null
                                && row.getTenantId() > 0
                                && row.getFactoryCode() != null
                                && !row.getFactoryCode().isBlank(),
                        "协调候选缺少实际组织");
                TenantContextHolder.clear();
                FactoryContextHolder.clear();
                TenantContextHolder.setTenantId(row.getTenantId());
                FactoryContextHolder.setFactoryCode(row.getFactoryCode());
                if (worker.deliver(row.getId())) n++;
            } catch (RuntimeException ex) {
                log.warn(
                        "修订协调未确认，保留原件 requestId={}，异常类型={}",
                        row.getId(),
                        ex.getClass().getSimpleName());
            } finally {
                TenantContextHolder.clear();
                FactoryContextHolder.clear();
                TenantContextHolder.setTenantId(tenant);
                FactoryContextHolder.setFactoryCode(factory);
                if (ts) TenantContextHolder.setTenantSkip();
                if (fs) FactoryContextHolder.setFactorySkip();
            }
        }
        return n;
    }
}
