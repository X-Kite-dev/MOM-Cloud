package com.kitex.mom.mes.task;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.mapper.MesPlanCompletionOutboxMapper;
import com.kitex.mom.mes.service.impl.MesPlanCompletionRetryService;
import com.kitex.mom.mes.service.impl.MesPlanCompletionWorker;
import com.xxl.job.core.handler.annotation.XxlJob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

/**
 * 每行进入实际租户工厂；没有作业员会话也能恢复已提交通知。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MesPlanCompletionJob {
    private final MesPlanCompletionOutboxMapper outboxes;
    private final MesPlanCompletionWorker worker;
    private final MesPlanCompletionRetryService retries;

    @XxlJob("mes_plan_completion_dispatch")
    public void dispatch() {
        log.info("MES完工通知已投递数量={}", dispatchDue(100));
    }

    public int dispatchDue(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("完工通知批量范围应为1至100");
        int delivered = 0;
        for (var row : outboxes.due(limit)) {
            if (row.getTenantId() == null
                    || row.getTenantId() <= 0
                    || row.getFactoryCode() == null
                    || row.getFactoryCode().isBlank()) {
                log.warn("完工通知调度行缺实际组织，outboxId={}", row.getId());
                continue;
            }
            Long beforeTenant = TenantContextHolder.getTenantId();
            boolean beforeTenantSkip = TenantContextHolder.getTenantSkip();
            String beforeFactory = FactoryContextHolder.getFactoryCode();
            boolean beforeFactorySkip = Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
            try {
                TenantContextHolder.clear();
                FactoryContextHolder.clear();
                boolean ok =
                        TenantBroker.applyAs(
                                row.getTenantId(),
                                ignored -> {
                                    FactoryContextHolder.setFactoryCode(row.getFactoryCode());
                                    try {
                                        return worker.deliverOne(row.getId(), row.getFactoryCode());
                                    } catch (RuntimeException failure) {
                                        retries.mark(row.getId(), row.getFactoryCode(), failure);
                                        return false;
                                    }
                                });
                if (ok) delivered++;
            } catch (RuntimeException failure) {
                log.warn(
                        "完工通知调度未完成，outboxId={}，errorType={}",
                        row.getId(),
                        failure.getClass().getSimpleName());
            } finally {
                TenantContextHolder.clear();
                FactoryContextHolder.clear();
                TenantContextHolder.setTenantId(beforeTenant);
                if (beforeTenantSkip) TenantContextHolder.setTenantSkip();
                FactoryContextHolder.setFactoryCode(beforeFactory);
                if (beforeFactorySkip) FactoryContextHolder.setFactorySkip();
            }
        }
        return delivered;
    }
}
