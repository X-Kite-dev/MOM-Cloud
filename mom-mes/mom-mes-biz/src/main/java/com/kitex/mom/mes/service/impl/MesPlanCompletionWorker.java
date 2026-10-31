package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.mes.mapper.MesPlanCompletionOutboxMapper;
import com.kitex.mom.plan.dto.PlanMesCompletionDTO;
import com.kitex.mom.plan.feign.RemotePlnMesCompletionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import static com.kitex.mom.mes.service.impl.MesPlanCompletionCodec.require;

/**
 * 提交后按永久事件投递；远端成功而本地失败时重投仍返回原处理回执。
 */
@Service
@RequiredArgsConstructor
public class MesPlanCompletionWorker {
    private final MesPlanCompletionOutboxMapper outboxes;
    private final RemotePlnMesCompletionService remote;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean deliverOne(Long id, String factory) {
        Long tenant = MesPlanCompletionOutboxService.scope(factory);
        var row = outboxes.locked(tenant, factory, id);
        if (row == null
                || !List.of("PENDING", "RETRY_WAIT").contains(row.getStatus())
                || row.getNextAttemptTime() == null
                || row.getNextAttemptTime().isAfter(LocalDateTime.now())) return false;
        require(
                row.getAttemptCount() != null
                        && row.getAttemptCount() >= 0
                        && row.getAttemptCount() <= 1_000_000,
                "完工通知投递次数无效");
        require(
                Objects.equals(
                        row.getPayloadFingerprint(),
                        MesPlanCompletionCodec.hash(row.getPayloadJson())),
                "完工通知原文摘要不符");
        var event = MesPlanCompletionCodec.decode(json, row.getPayloadJson());
        require(
                Objects.equals(event.getTenantId(), tenant)
                        && Objects.equals(event.getFactoryCode(), factory)
                        && Objects.equals(event.getOutboxId(), id)
                        && Objects.equals(event.getEventId(), row.getEventId())
                        && Objects.equals(event.getTaskId(), row.getTaskId())
                        && Objects.equals(event.getTaskCode(), row.getTaskCode())
                        && Objects.equals(event.getCompletionVersion(), row.getCompletionVersion())
                        && event.getSources() != null
                        && !event.getSources().isEmpty(),
                "完工通知信封与原件不一致");
        var response =
                remote.reconcile(
                        new PlanMesCompletionDTO.Command()
                                .setOutboxId(id)
                                .setEventId(row.getEventId())
                                .setPayloadFingerprint(row.getPayloadFingerprint())
                                .setFactoryCode(factory));
        require(
                response != null && response.isOk() && response.getData() != null,
                "PLAN完工通知处理结果未知");
        var receipt = response.getData();
        require(
                Objects.equals(receipt.getOutboxId(), id)
                        && Objects.equals(receipt.getEventId(), row.getEventId())
                        && Objects.equals(
                        receipt.getPayloadFingerprint(), row.getPayloadFingerprint())
                        && receipt.getReceiptIds() != null
                        && receipt.getReceiptIds().size() == event.getSources().size()
                        && new HashSet<>(receipt.getReceiptIds()).size()
                        == receipt.getReceiptIds().size()
                        && receipt.getReceiptIds().stream()
                        .allMatch(value -> value != null && value > 0),
                "PLAN完工通知回执身份或完整范围不一致");
        require(
                outboxes.delivered(
                        tenant,
                        factory,
                        id,
                        Math.min(1_000_000, row.getAttemptCount() + 1),
                        LocalDateTime.now(),
                        MesPlanCompletionCodec.encode(json, receipt.getReceiptIds()))
                        == 1,
                "PLAN完工回执保存失败");
        return true;
    }
}
