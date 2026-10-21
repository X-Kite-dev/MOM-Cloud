package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.util.R;
import com.kitex.mom.plan.dto.PlanAutomationEventDTO;
import com.kitex.mom.plan.feign.RemotePlnAutomationEventService;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 单条WMS供应失效事实独立投递，远端成功而本地提交失败时依赖PLAN永久幂等安全重放。
 */
@Service
@RequiredArgsConstructor
public class WmsPlanShortageEventOutboxWorker {
    private final WmsPlanShortageEventOutboxMapper outboxMapper;
    private final WmsPlanShortageEventOperationMapper operationMapper;
    private final WmsAllocationBillMapper billMapper;
    private final WmsTransferVarianceCaseMapper varianceMapper;
    private final WmsTransferVarianceLineMapper varianceLineMapper;
    private final RemotePlnAutomationEventService remoteEventService;

    private static void require(boolean condition, String message) {
        if (!condition) throw new NonRetryableDeliveryException(message);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean deliverOne(Long outboxId) {
        WmsPlanShortageEventOutboxEntity outbox = outboxMapper.lockById(outboxId);
        if (outbox == null || !List.of("PENDING", "RETRY_WAIT").contains(outbox.getStatus())
                || outbox.getNextAttemptTime() == null || outbox.getNextAttemptTime().isAfter(LocalDateTime.now()))
            return false;
        require(outbox.getExpectedSupplyTime() != null && outbox.getExpectedSupplyTime().isAfter(LocalDateTime.now()),
                "预计供应时刻缺失或已过期，必须补充新的权威预测");
        require(StrUtil.isNotBlank(outbox.getPayloadJson()) && StrUtil.isNotBlank(outbox.getPayloadFingerprint())
                        && Objects.equals(outbox.getPayloadFingerprint(), hash(outbox.getPayloadJson())),
                "WMS缺料事件载荷缺失或指纹不一致");
        revalidateSource(outbox);

        String before = outbox.getStatus();
        int attempt = Objects.requireNonNullElse(outbox.getAttemptCount(), 0) + 1;
        outbox.setStatus("PROCESSING").setAttemptCount(attempt).setLastAttemptTime(LocalDateTime.now());
        require(outboxMapper.updateById(outbox) == 1, "WMS缺料事件投递占用失败");
        operation(outbox, "DELIVER:" + outbox.getEventId() + ':' + attempt, "DELIVERY_START", before,
                "PROCESSING", attempt, "开始向PLAN制造事件中心投递供应失效事实");

        PlanAutomationEventDTO.ReceiveCommand command = new PlanAutomationEventDTO.ReceiveCommand();
        command.setEventId(outbox.getEventId());
        command.setSourceSystem("WMS");
        command.setEventType(outbox.getEventType());
        command.setEventVersion(outbox.getEventVersion());
        command.setAggregateType("PLAN_MRP");
        command.setAggregateId(String.valueOf(outbox.getMrpId()));
        command.setFactoryCode(outbox.getFactoryCode());
        command.setOccurredTime(outbox.getOccurredTime());
        command.setPriority(12);
        command.setPayloadJson(outbox.getPayloadJson());
        R<PlanAutomationEventDTO.ReceiveReceipt> response = remoteEventService.receive(command);
        if (response == null || !response.isOk() || response.getData() == null)
            throw new IllegalStateException(response == null ? "PLAN制造事件中心无响应" : response.getMessage());
        PlanAutomationEventDTO.ReceiveReceipt receipt = response.getData();
        require(Objects.equals(receipt.getEventId(), outbox.getEventId()), "PLAN收件回执事件ID不一致");
        outbox.setStatus("DELIVERED").setNextAttemptTime(null).setDeliveredTime(LocalDateTime.now())
                .setPlanInboxId(receipt.getInboxId()).setPlanEventNo(receipt.getEventNo())
                .setPlanEventStatus(receipt.getStatus()).setLastErrorCode(null).setLastErrorMessage(null);
        require(outboxMapper.updateById(outbox) == 1, "WMS缺料事件成功状态保存失败");
        operation(outbox, "DELIVERED:" + outbox.getEventId() + ':' + attempt, "DELIVERY_SUCCESS", "PROCESSING",
                "DELIVERED", attempt, "PLAN收件号=" + receipt.getEventNo() + "，状态=" + receipt.getStatus()
                        + (Boolean.TRUE.equals(receipt.getDuplicate()) ? "，远端幂等重放" : ""));
        return true;
    }

    private void revalidateSource(WmsPlanShortageEventOutboxEntity outbox) {
        WmsAllocationBillEntity bill = billMapper.selectById(outbox.getAllocationBillId());
        require(bill != null && Objects.equals(bill.getFactoryCode(), outbox.getFactoryCode())
                        && Objects.equals(bill.getSourceMrpId(), outbox.getMrpId())
                        && Objects.equals(bill.getSourceBusinessNo(), outbox.getIssueNo())
                        && Objects.equals(bill.getSourceMaterialCode(), outbox.getMaterialCode()),
                "WMS供应失效来源调拨单不存在或结构化MRP来源已变化");
        if ("WMS_AGENT_TRANSFER_CANCELLED".equals(outbox.getSourceType())) {
            require(Set.of("CANCELLED", "PARTIALLY_CANCELLED").contains(bill.getAllocationState())
                            && bill.getCancelTime() != null,
                    "来源调拨单不再是已取消事实");
            return;
        }
        require("WMS_AGENT_TRANSFER_SHORT_RECEIPT".equals(outbox.getSourceType())
                && outbox.getVarianceCaseId() != null, "WMS缺料事件来源类型不受支持");
        WmsTransferVarianceCaseEntity variance = varianceMapper.selectById(outbox.getVarianceCaseId());
        require(variance != null && Objects.equals(variance.getAllocationBillId(), bill.getId())
                        && Set.of("SHORT", "MIXED").contains(variance.getVarianceType()),
                "调拨短收差异事实不存在或关联已变化");
        BigDecimal current = varianceLineMapper.selectList(
                        new LambdaQueryWrapper<WmsTransferVarianceLineEntity>()
                                .eq(WmsTransferVarianceLineEntity::getCaseId, variance.getId())
                                .eq(WmsTransferVarianceLineEntity::getMaterialCode, outbox.getMaterialCode()))
                .stream().map(WmsTransferVarianceLineEntity::getShortQuantity)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        require(current.compareTo(outbox.getShortageQuantity()) == 0 && current.signum() > 0,
                "调拨短收数量与冻结缺料事件不一致");
    }

    private void operation(WmsPlanShortageEventOutboxEntity outbox, String requestId, String type, String before,
                           String after, int attempt, String content) {
        require(operationMapper.insert(new WmsPlanShortageEventOperationEntity().setOutboxId(outbox.getId())
                        .setRequestId(requestId).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                        .setAttemptNo(attempt).setContent(content).setOperatorCode("SYSTEM").setOperatorName("WMS缺料事件投递器")
                        .setOperationTime(LocalDateTime.now()).setFactoryCode(outbox.getFactoryCode())) == 1,
                "WMS缺料事件投递履历保存失败");
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    public static class NonRetryableDeliveryException extends RuntimeException {
        public NonRetryableDeliveryException(String message) {
            super(message);
        }
    }
}
