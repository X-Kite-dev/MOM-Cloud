package com.kitex.mom.wms.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.wms.entity.WmsPlanShortageEventOperationEntity;
import com.kitex.mom.wms.entity.WmsPlanShortageEventOutboxEntity;
import com.kitex.mom.wms.mapper.WmsPlanShortageEventOperationMapper;
import com.kitex.mom.wms.mapper.WmsPlanShortageEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 投递事务回滚后，在独立事务中保存退避、待补数据或死信状态。
 */
@Service
@RequiredArgsConstructor
public class WmsPlanShortageEventOutboxFailureService {
    private final WmsPlanShortageEventOutboxMapper outboxMapper;
    private final WmsPlanShortageEventOperationMapper operationMapper;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean markFailure(Long outboxId, Throwable failure, boolean retryable) {
        WmsPlanShortageEventOutboxEntity outbox = outboxMapper.lockById(outboxId);
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
            next = LocalDateTime.now().plusSeconds(Math.min(60L * (1L << Math.min(Math.max(attempt - 1, 0), 10)), 3600L));
        }
        String message = compact(failure);
        outbox.setStatus(after).setAttemptCount(attempt).setNextAttemptTime(next).setLastAttemptTime(LocalDateTime.now())
                .setLastErrorCode(failure.getClass().getSimpleName()).setLastErrorMessage(message);
        require(outboxMapper.updateById(outbox) == 1, "WMS缺料事件失败状态保存失败");
        require(operationMapper.insert(new WmsPlanShortageEventOperationEntity().setOutboxId(outbox.getId())
                        .setRequestId("FAIL:" + outbox.getEventId() + ':' + attempt).setOperationType("DELIVERY_FAILURE")
                        .setBeforeStatus(before).setAfterStatus(after).setAttemptNo(attempt).setContent(message)
                        .setOperatorCode("SYSTEM").setOperatorName("WMS缺料事件投递器")
                        .setOperationTime(LocalDateTime.now()).setFactoryCode(outbox.getFactoryCode())) == 1,
                "WMS缺料事件失败履历保存失败");
        return true;
    }

    private String compact(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
