package com.kitex.mom.qms.service.impl;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.qms.mapper.QmsInspectionRevisionRunMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;

@Service
@RequiredArgsConstructor
@Slf4j
public class QmsInspectionRevisionRunDispatcher {
    private final QmsInspectionRevisionRunMapper runs;
    private final QmsInspectionRevisionRunEngine engine;

    public int scan() {
        QmsInspectionHistorySupport.require(
                RequestContextHolder.getRequestAttributes() == null
                        && SecurityContextHolder.getContext().getAuthentication() == null
                        && !TransactionSynchronizationManager.isActualTransactionActive(),
                "正式修订扫描仅供独立后台");
        QmsInspectionHistorySupport.require(Integer.valueOf(1).equals(runs.ready()), "正式修订结构未就绪");
        int n = 0;
        for (var row : runs.due()) {
            Long t = TenantContextHolder.getTenantId();
            String f = FactoryContextHolder.getFactoryCode();
            boolean ts = TenantContextHolder.getTenantSkip(),
                    fs = Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
            try {
                TenantContextHolder.clear();
                FactoryContextHolder.clear();
                TenantContextHolder.setTenantId(row.getTenantId());
                FactoryContextHolder.setFactoryCode(row.getFactoryCode());
                engine.advance(row.getId());
                n++;
            } catch (RuntimeException ex) {
                log.warn(
                        "正式修订待恢复 requestId={}，异常类型={}", row.getId(), ex.getClass().getSimpleName());
            } finally {
                TenantContextHolder.clear();
                FactoryContextHolder.clear();
                TenantContextHolder.setTenantId(t);
                FactoryContextHolder.setFactoryCode(f);
                if (ts) TenantContextHolder.setTenantSkip();
                if (fs) FactoryContextHolder.setFactorySkip();
            }
        }
        return n;
    }
}
