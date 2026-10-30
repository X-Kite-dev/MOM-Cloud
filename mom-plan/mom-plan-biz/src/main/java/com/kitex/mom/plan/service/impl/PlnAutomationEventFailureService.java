package com.kitex.mom.plan.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.plan.entity.PlnAutomationEventEntity;
import com.kitex.mom.plan.entity.PlnAutomationEventOperationEntity;
import com.kitex.mom.plan.entity.PlnAutomationEventPolicyEntity;
import com.kitex.mom.plan.mapper.PlnAutomationEventMapper;
import com.kitex.mom.plan.mapper.PlnAutomationEventOperationMapper;
import com.kitex.mom.plan.mapper.PlnAutomationEventPolicyMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 在业务执行回滚后，以独立事务保存退避、死信或人工接管状态。
 */
@Service
@RequiredArgsConstructor
public class PlnAutomationEventFailureService {
    private final PlnAutomationEventMapper eventMapper;
    private final PlnAutomationEventPolicyMapper policyMapper;
    private final PlnAutomationEventOperationMapper operationMapper;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean markFailure(Long eventInboxId, Throwable failure, boolean retryable) {
        PlnAutomationEventEntity event = eventMapper.lockById(eventInboxId);
        if (event == null
                || !("RECEIVED".equals(event.getStatus())
                || "RETRY_WAIT".equals(event.getStatus()))) return false;
        require(
                Objects.equals(event.getTenantId(), TenantContextHolder.getTenantId())
                        && event.getTenantId() != null
                        && event.getTenantId() > 0
                        && Objects.equals(
                        event.getFactoryCode(), FactoryContextHolder.getFactoryCode())
                        && event.getFactoryCode() != null
                        && !event.getFactoryCode().isBlank()
                        && !TenantContextHolder.getTenantSkip()
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
                "制造事件失败账必须进入实际租户工厂");
        int attempt = Objects.requireNonNullElse(event.getAttemptCount(), 0) + 1;
        int maxAttempts = Objects.requireNonNullElse(event.getMaxAttempts(), 5);
        String before = event.getStatus();
        String after;
        LocalDateTime next = null;
        if (!retryable) after = "WAITING_MANUAL";
        else if (attempt >= maxAttempts) after = "DEAD_LETTER";
        else {
            after = "RETRY_WAIT";
            next = LocalDateTime.now().plusSeconds(backoffSeconds(event, attempt));
        }
        String message = compactMessage(failure);
        event.setStatus(after)
                .setAttemptCount(attempt)
                .setNextAttemptTime(next)
                .setLastStartedTime(LocalDateTime.now())
                .setLastErrorCode(failure.getClass().getSimpleName())
                .setLastErrorMessage(message);
        require(
                eventMapper.update(
                        null,
                        new LambdaUpdateWrapper<PlnAutomationEventEntity>()
                                .eq(
                                        PlnAutomationEventEntity::getTenantId,
                                        event.getTenantId())
                                .eq(
                                        PlnAutomationEventEntity::getFactoryCode,
                                        event.getFactoryCode())
                                .eq(PlnAutomationEventEntity::getId, event.getId())
                                .eq(PlnAutomationEventEntity::getStatus, before)
                                .set(PlnAutomationEventEntity::getStatus, after)
                                .set(PlnAutomationEventEntity::getAttemptCount, attempt)
                                .set(PlnAutomationEventEntity::getNextAttemptTime, next)
                                .set(
                                        PlnAutomationEventEntity::getLastStartedTime,
                                        event.getLastStartedTime())
                                .set(
                                        PlnAutomationEventEntity::getLastErrorCode,
                                        event.getLastErrorCode())
                                .set(PlnAutomationEventEntity::getLastErrorMessage, message)
                                .set(
                                        PlnAutomationEventEntity::getUpdateTime,
                                        LocalDateTime.now()))
                        == 1,
                "事件失败状态保存失败");
        require(
                operationMapper.insert(
                        new PlnAutomationEventOperationEntity()
                                .setEventId(event.getId())
                                .setRequestId("FAIL:" + event.getEventNo() + ':' + attempt)
                                .setOperationType("PROCESS_FAILURE")
                                .setBeforeStatus(before)
                                .setAfterStatus(after)
                                .setAttemptNo(attempt)
                                .setContent(message)
                                .setOperatorCode("SYSTEM")
                                .setOperatorName("系统事件处理器")
                                .setOperationTime(LocalDateTime.now())
                                .setFactoryCode(event.getFactoryCode()))
                        == 1,
                "事件失败履历保存失败");
        return true;
    }

    private long backoffSeconds(PlnAutomationEventEntity event, int attempt) {
        int initial = 60, maximum = 3600;
        if (event.getPolicyId() != null) {
            PlnAutomationEventPolicyEntity policy = policyMapper.selectById(event.getPolicyId());
            if (policy != null) {
                initial = Objects.requireNonNullElse(policy.getInitialBackoffSeconds(), initial);
                maximum = Objects.requireNonNullElse(policy.getMaxBackoffSeconds(), maximum);
            }
        }
        long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 20);
        return Math.min((long) initial * multiplier, maximum);
    }

    private String compactMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
