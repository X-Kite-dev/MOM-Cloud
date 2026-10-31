package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesReworkCompletionCodec.require;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;

/** Joins production completion for enqueue; network delivery always follows commit. */
@Service
@RequiredArgsConstructor
@Slf4j
public class MesReworkCompletionCoordinator {
    private final MesReworkCompletionOutbox outbox;

    public Long notifyCompletion(Long taskId, String code, BigDecimal quantity) {
        return submit(taskId, code, quantity, false);
    }

    public Long retry(Long taskId) {
        return submit(taskId, null, null, true);
    }

    private Long submit(Long taskId, String code, BigDecimal quantity, boolean retry) {
        MesQualityGateSourceEvidence.scope();
        boolean joined = TransactionSynchronizationManager.isActualTransactionActive();
        if (joined)
            require(TransactionSynchronizationManager.isSynchronizationActive(), "返工完工事务未启用提交同步");
        outbox.prepare(taskId, code, quantity, retry);
        if (!joined) return outbox.deliver(taskId);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            outbox.deliver(taskId);
                        } catch (RuntimeException failure) {
                            log.warn(
                                    "返工完工已提交，原件保留待核对 taskId={}, failure={}",
                                    taskId,
                                    failure.getClass().getSimpleName());
                        }
                    }
                });
        return null;
    }
}
