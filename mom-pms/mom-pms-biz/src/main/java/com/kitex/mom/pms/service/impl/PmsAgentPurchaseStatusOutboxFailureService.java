package com.kitex.mom.pms.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.pms.entity.PmsAgentPurchaseStatusOperationEntity;
import com.kitex.mom.pms.entity.PmsAgentPurchaseStatusOutboxEntity;
import com.kitex.mom.pms.mapper.PmsAgentPurchaseStatusOperationMapper;
import com.kitex.mom.pms.mapper.PmsAgentPurchaseStatusOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PmsAgentPurchaseStatusOutboxFailureService {
    private final PmsAgentPurchaseStatusOutboxMapper outboxMapper;
    private final PmsAgentPurchaseStatusOperationMapper operationMapper;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean markFailure(Long outboxId, Throwable failure, boolean retryable, boolean sourceClosed) {
        PmsAgentPurchaseStatusOutboxEntity outbox = outboxMapper.lockById(outboxId);
        if (outbox == null || !List.of("PENDING", "RETRY_WAIT").contains(outbox.getStatus())) return false;
        int attempt = Objects.requireNonNullElse(outbox.getAttemptCount(), 0) + 1;
        int maxAttempts = Objects.requireNonNullElse(outbox.getMaxAttempts(), 8);
        String before = outbox.getStatus();
        String after;
        LocalDateTime next = null;
        if (sourceClosed) after = "SOURCE_CLOSED";
        else if (!retryable || attempt >= maxAttempts) after = "DEAD_LETTER";
        else {
            after = "RETRY_WAIT";
            next = LocalDateTime.now().plusSeconds(Math.min(60L * (1L << Math.min(Math.max(attempt - 1, 0), 10)), 3600L));
        }
        String message = compact(failure);
        outbox.setStatus(after).setAttemptCount(attempt).setNextAttemptTime(next)
                .setLastAttemptTime(LocalDateTime.now()).setLastErrorCode(failure.getClass().getSimpleName())
                .setLastErrorMessage(message);
        require(outboxMapper.updateById(outbox) == 1, "PMS采购状态事件失败状态保存失败");
        require(operationMapper.insert(new PmsAgentPurchaseStatusOperationEntity().setOutboxId(outbox.getId())
                        .setRequestId("FAIL:" + outbox.getEventId() + ':' + attempt).setOperationType("DELIVERY_FAILURE")
                        .setBeforeStatus(before).setAfterStatus(after).setAttemptNo(attempt).setContent(message)
                        .setOperatorCode("SYSTEM").setOperatorName("PMS采购状态投递器")
                        .setOperationTime(LocalDateTime.now()).setFactoryCode(outbox.getFactoryCode())) == 1,
                "PMS采购状态事件失败履历保存失败");
        return true;
    }

    private String compact(Throwable failure) {
        String value = failure.getMessage();
        if (value == null || value.isBlank()) value = failure.getClass().getSimpleName();
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
