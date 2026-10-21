package com.kitex.mom.pms.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.pms.entity.PmsPlanCommitmentEventOperationEntity;
import com.kitex.mom.pms.entity.PmsPlanCommitmentEventOutboxEntity;
import com.kitex.mom.pms.mapper.PmsPlanCommitmentEventOperationMapper;
import com.kitex.mom.pms.mapper.PmsPlanCommitmentEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PmsPlanCommitmentEventOutboxFailureService {
    private final PmsPlanCommitmentEventOutboxMapper outboxMapper;
    private final PmsPlanCommitmentEventOperationMapper operationMapper;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean markFailure(Long outboxId, Throwable failure, boolean retryable, boolean sourceClosed) {
        PmsPlanCommitmentEventOutboxEntity outbox = outboxMapper.lockById(outboxId);
        if (outbox == null || !List.of("PENDING", "RETRY_WAIT").contains(outbox.getStatus())) return false;
        int attempt = Objects.requireNonNullElse(outbox.getAttemptCount(), 0) + 1;
        int maxAttempts = Objects.requireNonNullElse(outbox.getMaxAttempts(), 8);
        String before = outbox.getStatus();
        String after;
        LocalDateTime next = null;
        if (sourceClosed) after = "SOURCE_CLOSED";
        else if (!retryable) after = "WAITING_DATA";
        else if (attempt >= maxAttempts) after = "DEAD_LETTER";
        else {
            after = "RETRY_WAIT";
            next = LocalDateTime.now().plusSeconds(Math.min(60L * (1L << Math.min(Math.max(attempt - 1, 0), 10)), 3600L));
        }
        String message = compact(failure);
        outbox.setStatus(after).setAttemptCount(attempt).setNextAttemptTime(next).setLastAttemptTime(LocalDateTime.now())
                .setLastErrorCode(failure.getClass().getSimpleName()).setLastErrorMessage(message);
        require(outboxMapper.updateById(outbox) == 1, "PMS承诺事件失败状态保存失败");
        require(operationMapper.insert(new PmsPlanCommitmentEventOperationEntity().setOutboxId(outbox.getId())
                        .setRequestId("FAIL:" + outbox.getEventId() + ':' + attempt).setOperationType("DELIVERY_FAILURE")
                        .setBeforeStatus(before).setAfterStatus(after).setAttemptNo(attempt).setContent(message)
                        .setOperatorCode("SYSTEM").setOperatorName("PMS承诺事件投递器")
                        .setOperationTime(LocalDateTime.now()).setFactoryCode(outbox.getFactoryCode())) == 1,
                "PMS承诺事件失败履历保存失败");
        return true;
    }

    private String compact(Throwable failure) {
        String value = failure.getMessage();
        if (value == null || value.isBlank()) value = failure.getClass().getSimpleName();
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
