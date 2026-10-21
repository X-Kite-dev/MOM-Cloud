package com.kitex.mom.tpm.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.plan.dto.PlanAutomationEventDTO;
import com.kitex.mom.plan.feign.RemotePlnAutomationEventService;
import com.kitex.mom.tpm.entity.TpmPlanEventDeliveryOperationEntity;
import com.kitex.mom.tpm.entity.TpmPlanEventOutboxEntity;
import com.kitex.mom.tpm.mapper.TpmPlanEventDeliveryOperationMapper;
import com.kitex.mom.tpm.mapper.TpmPlanEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 单条Outbox独立投递；远端已收件而本地提交失败时，依赖PLAN永久幂等收件安全重放。
 */
@Service
@RequiredArgsConstructor
public class TpmPlanEventOutboxWorker {
    private final TpmPlanEventOutboxMapper outboxMapper;
    private final TpmPlanEventDeliveryOperationMapper operationMapper;
    private final RemotePlnAutomationEventService remoteEventService;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean deliverOne(Long outboxId) {
        TpmPlanEventOutboxEntity outbox = outboxMapper.lockById(outboxId);
        if (outbox == null || !List.of("PENDING", "RETRY_WAIT").contains(outbox.getStatus())
                || outbox.getNextAttemptTime() == null || outbox.getNextAttemptTime().isAfter(LocalDateTime.now()))
            return false;
        if (outbox.getExpectedRecoveryTime() == null)
            throw new NonRetryableDeliveryException("预计恢复时间尚未提供，禁止向智能排产投递不完整设备事实");
        String before = outbox.getStatus();
        int attempt = Objects.requireNonNullElse(outbox.getAttemptCount(), 0) + 1;
        outbox.setStatus("PROCESSING").setAttemptCount(attempt).setLastAttemptTime(LocalDateTime.now());
        require(outboxMapper.updateById(outbox) == 1, "设备事件投递占用失败");
        operation(outbox, "DELIVER:" + outbox.getEventId() + ':' + attempt, "DELIVERY_START", before,
                "PROCESSING", attempt, "开始向PLAN制造事件中心投递");

        PlanAutomationEventDTO.ReceiveCommand command = new PlanAutomationEventDTO.ReceiveCommand();
        command.setEventId(outbox.getEventId());
        command.setSourceSystem("TPM");
        command.setEventType(outbox.getEventType());
        command.setEventVersion(outbox.getEventVersion());
        command.setAggregateType(outbox.getSourceType());
        command.setAggregateId(String.valueOf(outbox.getSourceId()));
        command.setFactoryCode(outbox.getFactoryCode());
        command.setOccurredTime(outbox.getOccurredTime());
        command.setPriority(10);
        command.setPayloadJson(outbox.getPayloadJson());
        R<PlanAutomationEventDTO.ReceiveReceipt> response = remoteEventService.receive(command);
        if (response == null || !response.isOk() || response.getData() == null)
            throw new IllegalStateException(response == null ? "PLAN制造事件中心无响应" : response.getMessage());
        PlanAutomationEventDTO.ReceiveReceipt receipt = response.getData();
        require(Objects.equals(receipt.getEventId(), outbox.getEventId()), "PLAN收件回执事件ID不一致");
        outbox.setStatus("DELIVERED").setNextAttemptTime(null).setDeliveredTime(LocalDateTime.now())
                .setPlanInboxId(receipt.getInboxId()).setPlanEventNo(receipt.getEventNo()).setPlanEventStatus(receipt.getStatus())
                .setLastErrorCode(null).setLastErrorMessage(null);
        require(outboxMapper.updateById(outbox) == 1, "设备事件投递成功状态保存失败");
        operation(outbox, "DELIVERED:" + outbox.getEventId() + ':' + attempt, "DELIVERY_SUCCESS", "PROCESSING",
                "DELIVERED", attempt, "PLAN收件号=" + receipt.getEventNo() + "，状态=" + receipt.getStatus()
                        + (Boolean.TRUE.equals(receipt.getDuplicate()) ? "，远端幂等重放" : ""));
        return true;
    }

    private void operation(TpmPlanEventOutboxEntity outbox, String requestId, String type, String before,
                           String after, int attempt, String content) {
        require(operationMapper.insert(new TpmPlanEventDeliveryOperationEntity().setOutboxId(outbox.getId())
                .setRequestId(requestId).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                .setAttemptNo(attempt).setContent(content).setOperatorCode("SYSTEM").setOperatorName("TPM事件投递器")
                .setOperationTime(LocalDateTime.now()).setFactoryCode(outbox.getFactoryCode())) == 1, "设备事件投递履历保存失败");
    }

    public static class NonRetryableDeliveryException extends RuntimeException {
        public NonRetryableDeliveryException(String message) {
            super(message);
        }
    }
}
