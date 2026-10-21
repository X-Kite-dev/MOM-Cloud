package com.kitex.mom.qms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mes.api.feign.RemoteMesTaskService;
import com.kitex.mom.mes.dto.MesQualityDispositionDTO;
import com.kitex.mom.mes.dto.MesSourceQuantityMappingDTO;
import com.kitex.mom.plan.dto.PlanAutomationEventDTO;
import com.kitex.mom.plan.feign.RemotePlnAutomationEventService;
import com.kitex.mom.qms.entity.QmsPlanEventOperationEntity;
import com.kitex.mom.qms.entity.QmsPlanEventOutboxEntity;
import com.kitex.mom.qms.entity.QmsPlanEventTargetEntity;
import com.kitex.mom.qms.entity.QmsReworkOrderEntity;
import com.kitex.mom.qms.mapper.QmsPlanEventOperationMapper;
import com.kitex.mom.qms.mapper.QmsPlanEventOutboxMapper;
import com.kitex.mom.qms.mapper.QmsPlanEventTargetMapper;
import com.kitex.mom.qms.service.QmsReworkOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * QMS事实准备、逐来源计划投递和汇总分别提交，保证多目标部分成功可恢复。
 */
@Service
@RequiredArgsConstructor
public class QmsPlanEventOutboxWorker {
    private final QmsPlanEventOutboxMapper outboxMapper;
    private final QmsPlanEventTargetMapper targetMapper;
    private final QmsPlanEventOperationMapper operationMapper;
    private final QmsQualityDispositionSourceService sourceService;
    private final QmsReworkOrderService reworkOrderService;
    private final RemoteMesTaskService remoteMesTaskService;
    private final RemotePlnAutomationEventService remoteEventService;
    private final ObjectMapper objectMapper;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean prepareOne(Long outboxId) {
        QmsPlanEventOutboxEntity outbox = outboxMapper.lockById(outboxId);
        if (outbox == null || !List.of("PENDING", "RETRY_WAIT").contains(outbox.getStatus())
                || outbox.getNextAttemptTime() == null || outbox.getNextAttemptTime().isAfter(LocalDateTime.now()))
            return false;
        String before = outbox.getStatus();
        int attempt = Objects.requireNonNullElse(outbox.getAttemptCount(), 0) + 1;
        QmsQualityDispositionSourceService.Source source = "ISOLATE".equals(outbox.getDispositionKind())
                ? sourceService.loadIsolation(outbox.getInspectionType(), outbox.getInspectionTaskId())
                : sourceService.load(outbox.getInspectionType(), outbox.getInspectionTaskId(), outbox.getHandleRecordId());
        require(Objects.equals(outbox.getSourceFingerprint(), sourceService.fingerprint(source)),
                "QMS处置权威事实已变化，禁止覆盖或投递旧证据");
        require(Objects.equals(outbox.getFactoryCode(), source.factoryCode())
                        && Objects.equals(outbox.getMesTaskCode(), source.mesTaskCode())
                        && Objects.equals(outbox.getMesProduceTaskCode(), source.mesProduceTaskCode()),
                "QMS处置的工厂或MES来源关联已变化");
        outbox.setStatus("PROCESSING").setAttemptCount(attempt).setLastAttemptTime(LocalDateTime.now());
        require(outboxMapper.updateById(outbox) == 1, "QMS质量事实准备占用失败");
        operation(outbox, null, "PREPARE:" + outbox.getEventId() + ':' + attempt, "PREPARE_START",
                before, "PROCESSING", attempt, "开始幂等同步MES并解析APS来源分配");

        if ("REWORK".equals(source.dispositionKind())) {
            QmsReworkOrderEntity order = reworkOrderService.create(source.inspectionType(), source.handleRecordId());
            if (order == null || !"MES_CREATED".equals(order.getStatus()) || StrUtil.isBlank(order.getMesTaskCode())) {
                throw new IllegalStateException(order == null ? "QMS返工协调单未生成"
                        : StrUtil.blankToDefault(order.getLastError(), "MES返工工单尚未成功创建"));
            }
        } else {
            String mesDisposition = "SMASH".equals(source.dispositionKind()) ? "SCRAP" : source.dispositionKind();
            R<Void> mesResponse = remoteMesTaskService.recordQualityDisposition(new MesQualityDispositionDTO()
                    .setRequestId(outbox.getMesRequestId()).setDispositionKind(mesDisposition)
                    .setInspectionType(source.inspectionType()).setInspectionTaskId(source.inspectionTaskId())
                    .setHandleRecordId(source.handleRecordId()).setSourceTaskCode(source.mesTaskCode())
                    .setSourceProduceTaskCode(source.mesProduceTaskCode())
                    .setMatCode(source.matCode()).setMatName(source.matName()).setMatSpec(source.matSpec())
                    .setBatchNo(source.batchNo()).setQuantity(source.quantity())
                    .setTaskQuantity(source.taskQuantity()).setUsableQuantity(source.usableQuantity())
                    .setHandledQuantity(source.handledQuantity()).setTaskResult(source.taskResult())
                    .setReason(source.reason()).setOperatorCode(source.operatorCode()).setOperatorName(source.operatorName())
                    .setOccurredTime(source.occurredTime()).setFactoryCode(source.factoryCode()));
            if (mesResponse == null || !mesResponse.isOk())
                throw new IllegalStateException(mesResponse == null ? "MES质量处置服务无响应" : mesResponse.getMessage());
        }

        R<MesSourceQuantityMappingDTO> mappingResponse = remoteMesTaskService.sourceQuantityMapping(
                outbox.getMesRequestId(), source.factoryCode());
        if (mappingResponse == null || !mappingResponse.isOk() || mappingResponse.getData() == null)
            throw new IllegalStateException(mappingResponse == null ? "MES来源映射服务无响应" : mappingResponse.getMessage());
        MesSourceQuantityMappingDTO mapping = mappingResponse.getData();
        if (!Boolean.TRUE.equals(mapping.getMappingComplete()) || StrUtil.isBlank(mapping.getMappingFingerprint())
                || mapping.getAllocations() == null || mapping.getAllocations().isEmpty()) {
            throw new NonRetryableDeliveryException("MES来源映射不完整：" + mapping.getMappingStatus()
                    + "；" + StrUtil.blankToDefault(mapping.getMappingMessage(), "缺少APS来源分配"));
        }
        String expectedEventKind = switch (source.dispositionKind()) {
            case "ISOLATE" -> "QUALITY_ISOLATED";
            case "RELEASE", "CONCESSION" -> "QUALITY_RELEASED";
            case "REWORK" -> "REWORK_CREATED";
            case "SMASH" -> "SCRAP";
            default -> throw new NonRetryableDeliveryException("不支持的QMS质量事实类型：" + source.dispositionKind());
        };
        require(Objects.equals(source.factoryCode(), mapping.getFactoryCode())
                && Objects.equals(outbox.getMesRequestId(), mapping.getRequestId())
                && expectedEventKind.equals(mapping.getEventKind()), "MES来源数量映射的请求、类型或工厂不一致");
        require(mapping.getEventQuantity() != null && mapping.getEventQuantity().compareTo(source.quantity()) == 0,
                "MES来源数量映射与QMS质量事实数量不守恒");
        if (StrUtil.isNotBlank(outbox.getMappingFingerprint())
                && !Objects.equals(outbox.getMappingFingerprint(), mapping.getMappingFingerprint())) {
            throw new NonRetryableDeliveryException("质量事件已冻结的MES来源映射发生变化，必须人工核对");
        }
        String mappingJson = json(mapping);
        List<QmsPlanEventTargetEntity> existingTargets = targetMapper.selectList(
                new LambdaQueryWrapper<QmsPlanEventTargetEntity>()
                        .eq(QmsPlanEventTargetEntity::getOutboxId, outbox.getId())
                        .orderByAsc(QmsPlanEventTargetEntity::getIssueNo));
        if (existingTargets.isEmpty()) {
            for (MesSourceQuantityMappingDTO.Allocation allocation : mapping.getAllocations()) {
                require(allocation != null && allocation.getIssueId() != null && StrUtil.isNotBlank(allocation.getIssueNo())
                                && allocation.getSourceAllocatedQuantity() != null && allocation.getSourceAllocatedQuantity().signum() > 0
                                && allocation.getEventQuantity() != null && allocation.getEventQuantity().signum() > 0,
                        "MES来源数量分录缺少PLAN计划、生产分配量或本次正数增量");
                QmsPlanEventTargetEntity target = target(outbox, source, mapping, allocation);
                require(targetMapper.insert(target) == 1, "QMS质量事实逐计划目标保存失败");
            }
        } else {
            require(existingTargets.size() == mapping.getAllocations().size(), "已冻结投递目标数量与当前MES来源映射不一致");
            Map<Long, QmsPlanEventTargetEntity> byIssue = new LinkedHashMap<>();
            for (QmsPlanEventTargetEntity target : existingTargets) byIssue.put(target.getIssueId(), target);
            for (MesSourceQuantityMappingDTO.Allocation allocation : mapping.getAllocations()) {
                QmsPlanEventTargetEntity expected = target(outbox, source, mapping, allocation);
                QmsPlanEventTargetEntity actual = byIssue.get(allocation.getIssueId());
                require(actual != null && Objects.equals(actual.getEventId(), expected.getEventId())
                                && Objects.equals(actual.getPayloadFingerprint(), expected.getPayloadFingerprint()),
                        "已冻结投递目标与当前MES来源映射不一致");
            }
        }
        int targetCount = Math.toIntExact(targetMapper.selectCount(new LambdaQueryWrapper<QmsPlanEventTargetEntity>()
                .eq(QmsPlanEventTargetEntity::getOutboxId, outbox.getId())));
        require(targetCount == mapping.getAllocations().size(), "QMS质量事实逐计划目标数量不守恒");
        outbox.setMesSyncStatus("SYNCED").setMesSyncedTime(LocalDateTime.now())
                .setMappingStatus(mapping.getMappingStatus()).setMappingFingerprint(mapping.getMappingFingerprint())
                .setMappingSnapshotJson(mappingJson).setTargetCount(targetCount).setStatus("READY")
                .setNextAttemptTime(LocalDateTime.now()).setLastErrorCode(null).setLastErrorMessage(null);
        require(outboxMapper.updateById(outbox) == 1, "QMS质量事实准备结果保存失败");
        operation(outbox, null, "PREPARED:" + outbox.getEventId() + ':' + attempt, "PREPARE_SUCCESS",
                "PROCESSING", "READY", attempt, "MES质量" + source.dispositionKind()
                        + "事实已幂等落账，权威映射解析出" + targetCount + "张PLAN来源计划");
        return true;
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean deliverTarget(Long targetId) {
        QmsPlanEventTargetEntity target = targetMapper.lockById(targetId);
        if (target == null || "DELIVERED".equals(target.getStatus())) return false;
        QmsPlanEventOutboxEntity outbox = outboxMapper.lockById(target.getOutboxId());
        require(outbox != null && "READY".equals(outbox.getStatus()), "QMS质量事件尚未完成来源准备");
        require(Objects.equals(outbox.getMappingFingerprint(), target.getMappingFingerprint())
                && Objects.equals(outbox.getFactoryCode(), target.getFactoryCode()), "投递目标与Outbox冻结证据不一致");
        int attempt = Objects.requireNonNullElse(target.getAttemptCount(), 0) + 1;
        operation(outbox, target.getId(), "TARGET:" + target.getEventId() + ':' + attempt, "TARGET_DELIVERY_START",
                target.getStatus(), "PENDING", attempt, "开始向PLAN投递来源计划" + target.getIssueNo());
        PlanAutomationEventDTO.ReceiveCommand command = new PlanAutomationEventDTO.ReceiveCommand();
        command.setEventId(target.getEventId());
        command.setSourceSystem("QMS");
        command.setEventType(outbox.getEventType());
        command.setEventVersion(outbox.getEventVersion());
        command.setAggregateType("PLN_ISSUE");
        command.setAggregateId(String.valueOf(target.getIssueId()));
        command.setFactoryCode(outbox.getFactoryCode());
        command.setOccurredTime(outbox.getOccurredTime());
        command.setPriority(15);
        command.setPayloadJson(target.getPayloadJson());
        R<PlanAutomationEventDTO.ReceiveReceipt> response = remoteEventService.receive(command);
        if (response == null || !response.isOk() || response.getData() == null)
            throw new IllegalStateException(response == null ? "PLAN制造事件中心无响应" : response.getMessage());
        PlanAutomationEventDTO.ReceiveReceipt receipt = response.getData();
        require(Objects.equals(receipt.getEventId(), target.getEventId()), "PLAN收件回执事件ID不一致");
        target.setStatus("DELIVERED").setAttemptCount(attempt).setLastAttemptTime(LocalDateTime.now())
                .setDeliveredTime(LocalDateTime.now()).setPlanInboxId(receipt.getInboxId())
                .setPlanEventNo(receipt.getEventNo()).setPlanEventStatus(receipt.getStatus())
                .setLastErrorCode(null).setLastErrorMessage(null);
        require(targetMapper.updateById(target) == 1, "QMS质量事实目标回执保存失败");
        operation(outbox, target.getId(), "TARGET-OK:" + target.getEventId() + ':' + attempt, "TARGET_DELIVERY_SUCCESS",
                "PENDING", "DELIVERED", attempt, "PLAN收件号=" + receipt.getEventNo() + "，状态=" + receipt.getStatus()
                        + (Boolean.TRUE.equals(receipt.getDuplicate()) ? "，远端幂等重放" : ""));
        return true;
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean finalizeOne(Long outboxId) {
        QmsPlanEventOutboxEntity outbox = outboxMapper.lockById(outboxId);
        if (outbox == null || !"READY".equals(outbox.getStatus())) return false;
        List<QmsPlanEventTargetEntity> targets = targetMapper.selectList(
                new LambdaQueryWrapper<QmsPlanEventTargetEntity>()
                        .eq(QmsPlanEventTargetEntity::getOutboxId, outboxId));
        require(!targets.isEmpty(), "QMS质量事件没有可投递的PLAN来源目标");
        int delivered = (int) targets.stream().filter(item -> "DELIVERED".equals(item.getStatus())).count();
        outbox.setTargetCount(targets.size()).setDeliveredTargetCount(delivered);
        if (delivered == targets.size()) {
            outbox.setStatus("DELIVERED").setNextAttemptTime(null).setDeliveredTime(LocalDateTime.now())
                    .setLastErrorCode(null).setLastErrorMessage(null);
            require(outboxMapper.updateById(outbox) == 1, "QMS质量事件完成状态保存失败");
            operation(outbox, null, "COMPLETE:" + outbox.getEventId(), "OUTBOX_DELIVERED", "READY", "DELIVERED",
                    Objects.requireNonNullElse(outbox.getAttemptCount(), 0), "全部" + delivered + "张PLAN来源计划已确认收件");
            return true;
        }
        int maxTargetAttempts = targets.stream().map(QmsPlanEventTargetEntity::getAttemptCount)
                .filter(Objects::nonNull).max(Integer::compareTo).orElse(1);
        boolean dead = maxTargetAttempts >= Objects.requireNonNullElse(outbox.getMaxAttempts(), 8);
        String after = dead ? "DEAD_LETTER" : "RETRY_WAIT";
        LocalDateTime next = dead ? null : LocalDateTime.now().plusSeconds(backoff(maxTargetAttempts));
        outbox.setStatus(after).setNextAttemptTime(next)
                .setLastErrorCode("TARGET_DELIVERY_INCOMPLETE")
                .setLastErrorMessage("已投递" + delivered + "/" + targets.size() + "张来源计划；未完成目标将安全重放");
        require(outboxMapper.updateById(outbox) == 1, "QMS质量事件退避状态保存失败");
        operation(outbox, null, "INCOMPLETE:" + outbox.getEventId() + ':' + maxTargetAttempts,
                "OUTBOX_INCOMPLETE", "READY", after, maxTargetAttempts, outbox.getLastErrorMessage());
        return false;
    }

    private QmsPlanEventTargetEntity target(QmsPlanEventOutboxEntity outbox,
                                            QmsQualityDispositionSourceService.Source source,
                                            MesSourceQuantityMappingDTO mapping,
                                            MesSourceQuantityMappingDTO.Allocation allocation) {
        String eventId = outbox.getEventId() + ":ISSUE:" + allocation.getIssueId();
        String callbackRequestId = "MES-SOURCE-QTY:" + outbox.getMesRequestId() + ':' + allocation.getIssueId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("qmsOutboxId", outbox.getId());
        payload.put("qmsEventId", outbox.getEventId());
        payload.put("qmsFactFingerprint", outbox.getSourceFingerprint());
        payload.put("inspectionType", source.inspectionType());
        payload.put("inspectionTaskId", source.inspectionTaskId());
        payload.put("handleRecordId", source.handleRecordId());
        payload.put("dispositionKind", source.dispositionKind());
        payload.put("dispositionQuantity", source.quantity());
        payload.put("reason", source.reason());
        payload.put("mesTaskCode", source.mesTaskCode());
        payload.put("mesProduceTaskCode", source.mesProduceTaskCode());
        payload.put("mesDispositionRequestId", outbox.getMesRequestId());
        payload.put("mesCallbackRequestId", callbackRequestId);
        payload.put("issueId", allocation.getIssueId());
        payload.put("issueNo", allocation.getIssueNo());
        payload.put("sourceAllocatedQuantity", allocation.getSourceAllocatedQuantity());
        payload.put("allocatedQuantity", allocation.getEventQuantity());
        payload.put("quantityAfter", allocation.getQuantityAfter());
        payload.put("mappingFingerprint", mapping.getMappingFingerprint());
        payload.put("mappingStatus", mapping.getMappingStatus());
        String serialized = json(payload);
        return new QmsPlanEventTargetEntity().setOutboxId(outbox.getId()).setEventId(eventId)
                .setIssueId(allocation.getIssueId()).setIssueNo(allocation.getIssueNo())
                .setAllocatedQuantity(allocation.getEventQuantity()).setMappingFingerprint(mapping.getMappingFingerprint())
                .setPayloadJson(serialized).setPayloadFingerprint(sourceService.hash(serialized))
                .setStatus("PENDING").setAttemptCount(0).setFactoryCode(outbox.getFactoryCode());
    }

    private void operation(QmsPlanEventOutboxEntity outbox, Long targetId, String requestId, String type,
                           String before, String after, int attempt, String content) {
        require(operationMapper.insert(new QmsPlanEventOperationEntity().setOutboxId(outbox.getId()).setTargetId(targetId)
                        .setRequestId(requestId).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                        .setAttemptNo(attempt).setContent(content).setOperatorCode("SYSTEM").setOperatorName("QMS事件投递器")
                        .setOperationTime(LocalDateTime.now()).setFactoryCode(outbox.getFactoryCode())) == 1,
                "QMS质量事件投递履历保存失败");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new CheckedException("QMS质量事件载荷序列化失败");
        }
    }

    private long backoff(int attempt) {
        return Math.min(60L * (1L << Math.min(Math.max(attempt - 1, 0), 10)), 3600L);
    }

    public static class NonRetryableDeliveryException extends RuntimeException {
        public NonRetryableDeliveryException(String message) {
            super(message);
        }
    }
}
