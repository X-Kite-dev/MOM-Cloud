package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.entity.MesRepairResumeOutbox;
import com.kitex.mom.mes.mapper.MesRepairResumeDeliveryOperationMapper;
import com.kitex.mom.mes.mapper.MesRepairResumeOutboxMapper;
import com.kitex.mom.plan.dto.PlanAutomationEventDTO;
import com.kitex.mom.plan.feign.RemotePlnAutomationEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 远端收件幂等；超时不猜测成功或失败，原事件ID安全续投。
 */
@Service
@RequiredArgsConstructor
public class MesRepairResumeEventWorker {
    private final MesRepairResumeOutboxMapper outboxes;
    private final MesRepairResumeDeliveryOperationMapper operations;
    private final RemotePlnAutomationEventService remoteEvents;

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean deliverOne(Long id) {
        MesRepairResumeOutbox row = outboxes.selectOne(new LambdaQueryWrapper<MesRepairResumeOutbox>()
                .eq(MesRepairResumeOutbox::getId, id).last("FOR UPDATE"));
        if (row == null || !List.of("PENDING", "RETRY_WAIT").contains(row.getStatus())
                || row.getNextAttemptTime() == null || row.getNextAttemptTime().isAfter(LocalDateTime.now()))
            return false;
        if (!Objects.equals(row.getPayloadFingerprint(), MesRepairResumeEventService.hash(row.getPayloadJson())))
            throw new CheckedException("MES维修复工事件载荷指纹不一致");
        String before = row.getStatus();
        PlanAutomationEventDTO.ReceiveCommand command = new PlanAutomationEventDTO.ReceiveCommand();
        command.setEventId(row.getEventId());
        command.setSourceSystem("MES");
        command.setEventType("MES_REPAIR_RESUMED");
        command.setEventVersion(1);
        command.setAggregateType("MES_REPAIR_RESUME");
        command.setAggregateId(String.valueOf(row.getSegmentId()));
        command.setFactoryCode(row.getFactoryCode());
        command.setOccurredTime(row.getResumedTime());
        command.setPriority(15);
        command.setPayloadJson(row.getPayloadJson());
        R<PlanAutomationEventDTO.ReceiveReceipt> response = remoteEvents.receive(command);
        if (response == null || !response.isOk() || response.getData() == null)
            throw new IllegalStateException("PLAN维修复工事件收件结果未知或失败");
        var receipt = response.getData();
        if (!Objects.equals(receipt.getEventId(), row.getEventId()) || receipt.getInboxId() == null)
            throw new CheckedException("PLAN维修复工事件收件回执身份不一致");
        row.setStatus("DELIVERED");
        row.setPlanInboxId(receipt.getInboxId());
        row.setPlanEventNo(receipt.getEventNo());
        row.setPlanEventStatus(receipt.getStatus());
        row.setDeliveredTime(LocalDateTime.now());
        row.setNextAttemptTime(null);
        row.setLastError(null);
        row.setAttemptCount(Objects.requireNonNullElse(row.getAttemptCount(), 0) + 1);
        if (outboxes.updateById(row) != 1) throw new CheckedException("MES维修复工事件投递回执保存失败");
        MesRepairResumeEventService.record(operations, row, "DELIVERED", before, "DELIVERED",
                row.getAttemptCount(), null, "PLAN收件=" + receipt.getEventNo() + "，状态=" + receipt.getStatus());
        return true;
    }
}
