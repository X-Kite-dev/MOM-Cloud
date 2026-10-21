package com.kitex.mom.pms.service.impl;

import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.pms.entity.PmsDeliveryCommitmentEntity;
import com.kitex.mom.pms.mapper.PmsDeliveryCommitmentMapper;
import com.kitex.mom.pms.service.PmsPlanCommitmentEventOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 将升级前已存在的活动供应商承诺逐批补入Outbox；正常变更仍由源业务事务实时入箱。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmsPlanCommitmentEventSourceReconciler {
    private final PmsDeliveryCommitmentMapper commitmentMapper;
    private final PmsPlanCommitmentEventOutboxService outboxService;

    public int reconcileMissing(int limit) {
        List<PmsDeliveryCommitmentEntity> rows = commitmentMapper.selectMissingPlanEvents(Math.max(1, Math.min(limit, 500)));
        int count = 0;
        for (PmsDeliveryCommitmentEntity row : rows) {
            try {
                if (row.getTenantId() == null) outboxService.enqueueActivated(row);
                else TenantBroker.applyAs(row.getTenantId(), ignored -> {
                    outboxService.enqueueActivated(row);
                    return Boolean.TRUE;
                });
                count++;
            } catch (RuntimeException exception) {
                log.error("PMS历史活动承诺补入Outbox失败，tenantId={}，commitmentId={}", row.getTenantId(), row.getId(), exception);
            }
        }
        return count;
    }
}
