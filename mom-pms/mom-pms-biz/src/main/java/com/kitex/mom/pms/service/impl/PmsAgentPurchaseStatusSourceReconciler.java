package com.kitex.mom.pms.service.impl;

import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.pms.entity.PmsOrderEntity;
import com.kitex.mom.pms.mapper.PmsAgentPurchaseStatusOutboxMapper;
import com.kitex.mom.pms.service.PmsAgentPurchaseStatusOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 将缺失的Agent采购驳回/撤单终止供给事实补入可靠状态Outbox。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmsAgentPurchaseStatusSourceReconciler {
    private final PmsAgentPurchaseStatusOutboxMapper outboxMapper;
    private final PmsAgentPurchaseStatusOutboxService outboxService;

    public int reconcileMissing(int limit) {
        int batchSize = Math.max(1, Math.min(limit, 500));
        List<PmsOrderEntity> rows = outboxMapper.selectMissingRejectedOrders(batchSize);
        int count = 0;
        for (PmsOrderEntity row : rows) {
            try {
                if (row.getTenantId() == null) outboxService.enqueueRejected(row, row.getCheckReason());
                else TenantBroker.applyAs(row.getTenantId(), ignored -> {
                    outboxService.enqueueRejected(row, row.getCheckReason());
                    return Boolean.TRUE;
                });
                count++;
            } catch (RuntimeException exception) {
                log.error("PMS历史Agent采购驳回补入Outbox失败，tenantId={}，orderId={}",
                        row.getTenantId(), row.getId(), exception);
            }
        }
        List<PmsOrderEntity> cancelledRows = outboxMapper.selectMissingCancelledOrders(batchSize);
        for (PmsOrderEntity row : cancelledRows) {
            try {
                if (row.getTenantId() == null) outboxService.enqueueCancelled(row, row.getCancelReason());
                else TenantBroker.applyAs(row.getTenantId(), ignored -> {
                    outboxService.enqueueCancelled(row, row.getCancelReason());
                    return Boolean.TRUE;
                });
                count++;
            } catch (RuntimeException exception) {
                log.error("PMS Agent采购撤单补入Outbox失败，tenantId={}，orderId={}",
                        row.getTenantId(), row.getId(), exception);
            }
        }
        return count;
    }
}
