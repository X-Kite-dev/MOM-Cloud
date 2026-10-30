package com.kitex.mom.qms.service.impl;

import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.qms.mapper.QmsInspectionRevisionRunMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/** 阶段推进器本身不持事务，网络与本地改判各自提交后再进入下一阶段。 */
@Service
@RequiredArgsConstructor
public class QmsInspectionRevisionRunEngine {
    private final QmsInspectionRevisionRunMapper runs;
    private final QmsInspectionRevisionCoordinationWorker preparation;
    private final QmsInspectionRevisionRunWorker network;
    private final QmsInspectionRevisionCommitService commit;

    public void advance(Long id) {
        QmsInspectionEventWorker.scope();
        QmsInspectionHistorySupport.require(
                !TransactionSynchronizationManager.isActualTransactionActive(), "正式协调器不得加入调用方事务");
        for (int i = 0; i < 5; i++) {
            var r =
                    runs.run(
                            id,
                            TenantContextHolder.getTenantId(),
                            FactoryContextHolder.getFactoryCode(),
                            false);
            if (r == null
                    || !Set.of("READY", "RETRY_WAIT").contains(r.getStatus())
                    || r.getNextAttemptAt() == null
                    || r.getNextAttemptAt().after(new Date())) return;
            if ("PREPARE".equals(r.getPhase())) {
                preparation.deliver(id);
                if (!network.advance(id)) return;
            } else if ("COMMIT".equals(r.getPhase())) {
                try {
                    commit.commit(id);
                } catch (RuntimeException ex) {
                    network.localFailure(id);
                    return;
                }
            } else if (!network.advance(id)) return;
        }
    }
}
