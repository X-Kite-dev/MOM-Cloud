package com.kitex.mom.tpm.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.tpm.entity.TpmPlanEventDeliveryOperationEntity;
import com.kitex.mom.tpm.entity.TpmPlanEventOutboxEntity;
import com.kitex.mom.tpm.mapper.TpmPlanEventDeliveryOperationMapper;
import com.kitex.mom.tpm.mapper.TpmPlanEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 投递业务事务回滚后，以独立事务保存退避或死信状态。
 */
@Service
@RequiredArgsConstructor
public class TpmPlanEventOutboxFailureService {
    private final TpmPlanEventOutboxMapper outboxMapper;
    private final TpmPlanEventDeliveryOperationMapper operationMapper;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean markFailure(Long outboxId, Throwable failure, boolean retryable) {
        TpmPlanEventOutboxEntity outbox = outboxMapper.lockById(outboxId);
        if (outbox == null || !("PENDING".equals(outbox.getStatus()) || "RETRY_WAIT".equals(outbox.getStatus())))
            return false;
        int attempt = Objects.requireNonNullElse(outbox.getAttemptCount(), 0) + 1;
        int maxAttempts = Objects.requireNonNullElse(outbox.getMaxAttempts(), 8);
        String before = outbox.getStatus();
        String after;
        LocalDateTime next = null;
        if (!retryable) after = "WAITING_DATA";
        else if (attempt >= maxAttempts) after = "DEAD_LETTER";
        else {
            after = "RETRY_WAIT";
            long seconds = Math.min(60L * (1L << Math.min(Math.max(attempt - 1, 0), 10)), 3600L);
            next = LocalDateTime.now().plusSeconds(seconds);
        }
        String message = compact(failure);
        outbox.setStatus(after).setAttemptCount(attempt).setNextAttemptTime(next).setLastAttemptTime(LocalDateTime.now())
                .setLastErrorCode(failure.getClass().getSimpleName()).setLastErrorMessage(message);
        require(outboxMapper.updateById(outbox) == 1, "设备事件投递失败状态保存失败");
        require(operationMapper.insert(new TpmPlanEventDeliveryOperationEntity().setOutboxId(outbox.getId())
                .setRequestId("FAIL:" + outbox.getEventId() + ':' + attempt).setOperationType("DELIVERY_FAILURE")
                .setBeforeStatus(before).setAfterStatus(after).setAttemptNo(attempt).setContent(message)
                .setOperatorCode("SYSTEM").setOperatorName("TPM事件投递器").setOperationTime(LocalDateTime.now())
                .setFactoryCode(outbox.getFactoryCode())) == 1, "设备事件投递失败履历保存失败");
        return true;
    }

    private String compact(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
