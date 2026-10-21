package com.kitex.mom.mdm.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.entity.MdmPlanResourceEventOperationEntity;
import com.kitex.mom.mdm.entity.MdmPlanResourceEventOutboxEntity;
import com.kitex.mom.mdm.mapper.MdmPlanResourceEventOperationMapper;
import com.kitex.mom.mdm.mapper.MdmPlanResourceEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 投递事务回滚后，独立保存退避、死信或来源关闭状态。
 */
@Service
@RequiredArgsConstructor
public class MdmPlanResourceEventOutboxFailureService {
    private final MdmPlanResourceEventOutboxMapper outboxMapper;
    private final MdmPlanResourceEventOperationMapper operationMapper;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean closeExpired(Long outboxId) {
        MdmPlanResourceEventOutboxEntity outbox = outboxMapper.lockById(outboxId);
        if (outbox == null || !"ACTIVE".equals(outbox.getSourceStatus())
                || MdmPlanResourceEventOutboxServiceImpl.DEVICE_RECOVERY.equals(outbox.getSourceType())
                || outbox.getExpectedRecoveryTime() == null
                || outbox.getExpectedRecoveryTime().isAfter(LocalDateTime.now())) return false;
        String before = outbox.getStatus();
        outbox.setSourceStatus("CANCELLED").setSourceClosedTime(outbox.getExpectedRecoveryTime());
        if ("PENDING".equals(before) || "RETRY_WAIT".equals(before) || "DEAD_LETTER".equals(before)) {
            outbox.setStatus("CANCELLED").setNextAttemptTime(null).setLastErrorCode(null)
                    .setLastErrorMessage("MDM不可用窗口已经自然结束");
        }
        require(outboxMapper.updateById(outbox) == 1, "MDM资源事件到期状态保存失败");
        require(operationMapper.insert(new MdmPlanResourceEventOperationEntity().setOutboxId(outbox.getId())
                        .setRequestId("EXPIRED:" + outbox.getEventId()).setOperationType("SOURCE_EXPIRED")
                        .setBeforeStatus(before).setAfterStatus(outbox.getStatus())
                        .setAttemptNo(Objects.requireNonNullElse(outbox.getAttemptCount(), 0))
                        .setContent("MDM不可用窗口已于" + outbox.getExpectedRecoveryTime() + "自然结束")
                        .setOperatorCode("SYSTEM").setOperatorName("MDM资源事件投递器")
                        .setOperationTime(LocalDateTime.now()).setFactoryCode(outbox.getFactoryCode())) == 1,
                "MDM资源事件到期履历保存失败");
        return true;
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean markFailure(Long outboxId, Throwable failure, boolean retryable, boolean sourceClosed) {
        MdmPlanResourceEventOutboxEntity outbox = outboxMapper.lockById(outboxId);
        if (outbox == null || !("PENDING".equals(outbox.getStatus()) || "RETRY_WAIT".equals(outbox.getStatus())))
            return false;
        int attempt = Objects.requireNonNullElse(outbox.getAttemptCount(), 0) + 1;
        int maxAttempts = Objects.requireNonNullElse(outbox.getMaxAttempts(), 8);
        String before = outbox.getStatus();
        String after;
        LocalDateTime next = null;
        if (sourceClosed) after = "CANCELLED";
        else if (!retryable || attempt >= maxAttempts) after = "DEAD_LETTER";
        else {
            after = "RETRY_WAIT";
            next = LocalDateTime.now().plusSeconds(Math.min(60L * (1L << Math.min(Math.max(attempt - 1, 0), 10)), 3600L));
        }
        String message = compact(failure);
        outbox.setStatus(after).setAttemptCount(attempt).setNextAttemptTime(next).setLastAttemptTime(LocalDateTime.now())
                .setLastErrorCode(failure.getClass().getSimpleName()).setLastErrorMessage(message);
        if (sourceClosed) outbox.setSourceStatus("CANCELLED").setSourceClosedTime(LocalDateTime.now());
        require(outboxMapper.updateById(outbox) == 1, "MDM资源事件失败状态保存失败");
        require(operationMapper.insert(new MdmPlanResourceEventOperationEntity().setOutboxId(outbox.getId())
                        .setRequestId("FAIL:" + outbox.getEventId() + ':' + attempt).setOperationType("DELIVERY_FAILURE")
                        .setBeforeStatus(before).setAfterStatus(after).setAttemptNo(attempt).setContent(message)
                        .setOperatorCode("SYSTEM").setOperatorName("MDM资源事件投递器")
                        .setOperationTime(LocalDateTime.now()).setFactoryCode(outbox.getFactoryCode())) == 1,
                "MDM资源事件失败履历保存失败");
        return true;
    }

    private String compact(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
