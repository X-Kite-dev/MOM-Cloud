package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
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
import java.util.Set;

import static com.kitex.mom.mes.service.impl.MesPlanCompletionCodec.require;

/**
 * 仅证明PLAN永久收件；事件实际应用另由PLAN收件箱状态及逐来源回执证明。
 */
@Service
@RequiredArgsConstructor
public class MesPlanCompletionReopenEventWorker {
    private final MesPlanCompletionReopenOutboxMapper outboxMapper;
    private final MesPlanCompletionReopenOperationMapper operationMapper;
    private final RemotePlnAutomationEventService remoteEventService;
    private final ObjectMapper json;

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean deliverOne(Long id) {
        String factory = FactoryContextHolder.getFactoryCode();
        Long tenant = MesPlanCompletionOutboxService.scope(factory);
        require(id != null && id > 0, "反向完成原件主键无效");
        var row = outboxMapper.locked(tenant, factory, id);
        if (row == null
                || !List.of("PENDING", "RETRY_WAIT").contains(row.getStatus())
                || row.getNextAttemptTime() == null
                || row.getNextAttemptTime().isAfter(LocalDateTime.now())) return false;
        require(
                row.getAttemptCount() != null
                        && row.getAttemptCount() >= 0
                        && row.getAttemptCount() < 100,
                "反向完成投递次数无效");
        var payload = MesCompletionReopenCodec.read(json, row);
        var command = new PlanAutomationEventDTO.ReceiveCommand();
        command.setEventId(row.getEventId());
        command.setSourceSystem("MES");
        command.setEventType("MES_COMPLETION_REOPENED");
        command.setEventVersion(MesCompletionReopenCodec.version(payload));
        command.setAggregateType("MES_TASK");
        command.setAggregateId(String.valueOf(row.getTaskId()));
        command.setFactoryCode(factory);
        command.setOccurredTime(row.getReopenedTime());
        command.setPriority(10);
        command.setPayloadJson(row.getPayloadJson());
        var response = remoteEventService.receive(command);
        require(
                response != null && response.isOk() && response.getData() != null,
                "PLAN反向完成收件结果未知");
        var receipt = response.getData();
        require(
                Objects.equals(receipt.getEventId(), row.getEventId())
                        && receipt.getInboxId() != null
                        && receipt.getInboxId() > 0
                        && receipt.getEventNo() != null
                        && !receipt.getEventNo().isBlank()
                        && receipt.getEventNo().length() <= 100
                        && receipt.getStatus() != null
                        && Set.of(
                                "RECEIVED",
                                "WAITING_MANUAL",
                                "PROCESSING",
                                "SUCCEEDED",
                                "RETRY_WAIT",
                                "DEAD_LETTER",
                                "IGNORED")
                        .contains(receipt.getStatus()),
                "PLAN反向完成收件回执身份或状态不完整");
        int attempt = row.getAttemptCount() + 1;
        String before = row.getStatus();
        require(
                outboxMapper.state(
                        tenant,
                        factory,
                        id,
                        before,
                        "DELIVERED",
                        attempt,
                        null,
                        LocalDateTime.now(),
                        receipt.getInboxId(),
                        receipt.getEventNo(),
                        receipt.getStatus(),
                        null,
                        row.getRetryRequestId())
                        == 1,
                "PLAN收件回执保存失败");
        MesPlanCompletionReopenEventService.record(
                operationMapper,
                row,
                "DELIVERED",
                before,
                "DELIVERED",
                attempt,
                null,
                "PLAN永久收件=" + receipt.getEventNo() + "，处理状态=" + receipt.getStatus());
        return true;
    }
}
