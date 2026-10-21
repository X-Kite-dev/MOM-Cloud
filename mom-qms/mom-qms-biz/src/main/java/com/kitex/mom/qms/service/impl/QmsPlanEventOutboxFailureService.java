package com.kitex.mom.qms.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.qms.entity.QmsPlanEventOperationEntity;
import com.kitex.mom.qms.entity.QmsPlanEventOutboxEntity;
import com.kitex.mom.qms.entity.QmsPlanEventTargetEntity;
import com.kitex.mom.qms.mapper.QmsPlanEventOperationMapper;
import com.kitex.mom.qms.mapper.QmsPlanEventOutboxMapper;
import com.kitex.mom.qms.mapper.QmsPlanEventTargetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 远端调用事务回滚后，独立保存准备/目标投递失败证据。
 */
@Service
@RequiredArgsConstructor
public class QmsPlanEventOutboxFailureService {
    private final QmsPlanEventOutboxMapper outboxMapper;
    private final QmsPlanEventTargetMapper targetMapper;
    private final QmsPlanEventOperationMapper operationMapper;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean markPrepareFailure(Long outboxId, Throwable failure, boolean retryable) {
        QmsPlanEventOutboxEntity outbox = outboxMapper.lockById(outboxId);
        if (outbox == null || !List.of("PENDING", "RETRY_WAIT").contains(outbox.getStatus())) return false;
        int attempt = Objects.requireNonNullElse(outbox.getAttemptCount(), 0) + 1;
        int maxAttempts = Objects.requireNonNullElse(outbox.getMaxAttempts(), 8);
        String before = outbox.getStatus();
        String after = !retryable ? "WAITING_DATA" : attempt >= maxAttempts ? "DEAD_LETTER" : "RETRY_WAIT";
        LocalDateTime next = "RETRY_WAIT".equals(after) ? LocalDateTime.now().plusSeconds(backoff(attempt)) : null;
        String message = compact(failure);
        outbox.setStatus(after).setAttemptCount(attempt).setNextAttemptTime(next).setLastAttemptTime(LocalDateTime.now())
                .setLastErrorCode(failure.getClass().getSimpleName()).setLastErrorMessage(message);
        require(outboxMapper.updateById(outbox) == 1, "QMS质量事件准备失败状态保存失败");
        operation(outbox, null, "PREPARE-FAIL:" + outbox.getEventId() + ':' + attempt, "PREPARE_FAILURE",
                before, after, attempt, message);
        return true;
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean markTargetFailure(Long targetId, Throwable failure) {
        QmsPlanEventTargetEntity target = targetMapper.lockById(targetId);
        if (target == null || "DELIVERED".equals(target.getStatus())) return false;
        QmsPlanEventOutboxEntity outbox = outboxMapper.lockById(target.getOutboxId());
        require(outbox != null && "READY".equals(outbox.getStatus()), "QMS质量事件不在逐目标投递阶段");
        int attempt = Objects.requireNonNullElse(target.getAttemptCount(), 0) + 1;
        String message = compact(failure);
        target.setStatus("PENDING").setAttemptCount(attempt).setLastAttemptTime(LocalDateTime.now())
                .setLastErrorCode(failure.getClass().getSimpleName()).setLastErrorMessage(message);
        require(targetMapper.updateById(target) == 1, "QMS质量事件目标失败状态保存失败");
        operation(outbox, target.getId(), "TARGET-FAIL:" + target.getEventId() + ':' + attempt,
                "TARGET_DELIVERY_FAILURE", "PENDING", "PENDING", attempt, message);
        return true;
    }

    private void operation(QmsPlanEventOutboxEntity outbox, Long targetId, String requestId, String type,
                           String before, String after, int attempt, String content) {
        require(operationMapper.insert(new QmsPlanEventOperationEntity().setOutboxId(outbox.getId()).setTargetId(targetId)
                        .setRequestId(requestId).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                        .setAttemptNo(attempt).setContent(content).setOperatorCode("SYSTEM").setOperatorName("QMS事件投递器")
                        .setOperationTime(LocalDateTime.now()).setFactoryCode(outbox.getFactoryCode())) == 1,
                "QMS质量事件失败履历保存失败");
    }

    private long backoff(int attempt) {
        return Math.min(60L * (1L << Math.min(Math.max(attempt - 1, 0), 10)), 3600L);
    }

    private String compact(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.isBlank()) message = current.getClass().getSimpleName();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
