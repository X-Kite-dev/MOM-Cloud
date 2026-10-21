package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.entity.MesPlanCompletionReopenOutboxEntity;
import com.kitex.mom.mes.mapper.MesPlanCompletionReopenOperationMapper;
import com.kitex.mom.mes.mapper.MesPlanCompletionReopenOutboxMapper;
import com.kitex.mom.plan.dto.PlanAutomationEventDTO;
import com.kitex.mom.plan.feign.RemotePlnAutomationEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** 将MES完工重新打开Outbox幂等投递到PLAN制造事件中心。 */
@Service
@RequiredArgsConstructor
public class MesPlanCompletionReopenEventWorker {

    private final MesPlanCompletionReopenOutboxMapper outboxMapper;
    private final MesPlanCompletionReopenOperationMapper operationMapper;
    private final RemotePlnAutomationEventService remoteEventService;

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean deliverOne(Long id) {
        MesPlanCompletionReopenOutboxEntity row = outboxMapper.selectOne(
                new LambdaQueryWrapper<MesPlanCompletionReopenOutboxEntity>()
                        .eq(MesPlanCompletionReopenOutboxEntity::getId, id).last("FOR UPDATE"));
        if (row == null || !List.of("PENDING", "RETRY_WAIT").contains(row.getStatus())
                || row.getNextAttemptTime() == null || row.getNextAttemptTime().isAfter(LocalDateTime.now())) {
            return false;
        }
        if (!Objects.equals(row.getPayloadFingerprint(),
                MesPlanCompletionReopenEventService.hash(row.getPayloadJson()))) {
            throw new CheckedException("MES完工重新打开事件载荷指纹不一致");
        }
        String before = row.getStatus();
        PlanAutomationEventDTO.ReceiveCommand command = new PlanAutomationEventDTO.ReceiveCommand();
        command.setEventId(row.getEventId());
        command.setSourceSystem("MES");
        command.setEventType("MES_COMPLETION_REOPENED");
        command.setEventVersion(1);
        command.setAggregateType("MES_TASK");
        command.setAggregateId(String.valueOf(row.getTaskId()));
        command.setFactoryCode(row.getFactoryCode());
        command.setOccurredTime(row.getReopenedTime());
        command.setPriority(10);
        command.setPayloadJson(row.getPayloadJson());
        R<PlanAutomationEventDTO.ReceiveReceipt> response = remoteEventService.receive(command);
        if (response == null || !response.isOk() || response.getData() == null) {
            throw new IllegalStateException("PLAN完工重新打开事件收件结果未知或失败");
        }
        PlanAutomationEventDTO.ReceiveReceipt receipt = response.getData();
        if (!Objects.equals(receipt.getEventId(), row.getEventId()) || receipt.getInboxId() == null) {
            throw new CheckedException("PLAN完工重新打开事件收件回执身份不一致");
        }
        row.setStatus("DELIVERED").setPlanInboxId(receipt.getInboxId())
                .setPlanEventNo(receipt.getEventNo()).setPlanEventStatus(receipt.getStatus())
                .setDeliveredTime(LocalDateTime.now()).setNextAttemptTime(null).setLastError(null)
                .setAttemptCount(Objects.requireNonNullElse(row.getAttemptCount(), 0) + 1);
        if (outboxMapper.updateById(row) != 1) throw new CheckedException("PLAN收件回执保存失败");
        MesPlanCompletionReopenEventService.record(operationMapper, row, "DELIVERED", before,
                "DELIVERED", row.getAttemptCount(), null,
                "PLAN收件=" + receipt.getEventNo() + "，状态=" + receipt.getStatus());
        return true;
    }
}
