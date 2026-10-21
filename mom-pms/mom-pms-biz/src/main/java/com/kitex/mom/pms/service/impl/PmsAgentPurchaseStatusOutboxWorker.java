package com.kitex.mom.pms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.util.R;
import com.kitex.mom.plan.dto.PlanAgentPurchaseRejectionDTO;
import com.kitex.mom.plan.feign.RemotePlanMrpAgentService;
import com.kitex.mom.pms.entity.PmsAgentPurchaseStatusOperationEntity;
import com.kitex.mom.pms.entity.PmsAgentPurchaseStatusOutboxEntity;
import com.kitex.mom.pms.entity.PmsOrderDetailEntity;
import com.kitex.mom.pms.entity.PmsOrderEntity;
import com.kitex.mom.pms.mapper.PmsAgentPurchaseStatusOperationMapper;
import com.kitex.mom.pms.mapper.PmsAgentPurchaseStatusOutboxMapper;
import com.kitex.mom.pms.mapper.PmsOrderDetailMapper;
import com.kitex.mom.pms.mapper.PmsOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 单条采购状态事实独立投递，PLAN永久回执保证远端成功后的安全重放。
 */
@Service
@RequiredArgsConstructor
public class PmsAgentPurchaseStatusOutboxWorker {
    private final PmsAgentPurchaseStatusOutboxMapper outboxMapper;
    private final PmsAgentPurchaseStatusOperationMapper operationMapper;
    private final PmsOrderMapper orderMapper;
    private final PmsOrderDetailMapper detailMapper;
    private final RemotePlanMrpAgentService remotePlanMrpAgentService;

    private static void require(boolean condition, String message) {
        if (!condition) throw new NonRetryableDeliveryException(message);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean deliverOne(Long outboxId) {
        PmsAgentPurchaseStatusOutboxEntity outbox = outboxMapper.lockById(outboxId);
        if (outbox == null || !List.of("PENDING", "RETRY_WAIT").contains(outbox.getStatus())
                || outbox.getNextAttemptTime() == null || outbox.getNextAttemptTime().isAfter(LocalDateTime.now())) {
            return false;
        }
        revalidateSource(outbox);
        String before = outbox.getStatus();
        int attempt = Objects.requireNonNullElse(outbox.getAttemptCount(), 0) + 1;
        outbox.setStatus("PROCESSING").setAttemptCount(attempt).setLastAttemptTime(LocalDateTime.now());
        require(outboxMapper.updateById(outbox) == 1, "PMS采购状态事件投递占用失败");
        operation(outbox, "DELIVER:" + outbox.getEventId() + ':' + attempt, "DELIVERY_START",
                before, "PROCESSING", attempt, "开始向PLAN投递Agent采购订单终止供给事实");

        PlanAgentPurchaseRejectionDTO.Command command = new PlanAgentPurchaseRejectionDTO.Command()
                .setEventId(outbox.getEventId()).setSourceFingerprint(outbox.getSourceFingerprint())
                .setAgentRequestId(outbox.getAgentRequestId()).setMrpId(outbox.getMrpId())
                .setPmsOrderId(outbox.getPmsOrderId()).setPmsOrderNo(outbox.getPmsOrderNo())
                .setPmsOrderDetailId(outbox.getPmsOrderDetailId()).setPmsPlanNo(outbox.getPmsPlanNo())
                .setOrderStatus(outbox.getOrderStatus()).setRejectReason(outbox.getReason())
                .setRejectedTime(outbox.getOccurredTime()).setFactoryCode(outbox.getFactoryCode());
        R<PlanAgentPurchaseRejectionDTO.Receipt> response = remotePlanMrpAgentService.rejectPurchaseOrder(command);
        if (response == null || !response.isOk() || response.getData() == null) {
            throw new IllegalStateException(response == null ? "PLAN采购状态回写无响应" : response.getMsg());
        }
        PlanAgentPurchaseRejectionDTO.Receipt receipt = response.getData();
        require(Objects.equals(receipt.getEventId(), outbox.getEventId())
                        && Objects.equals(receipt.getMrpId(), outbox.getMrpId())
                        && List.of("APPLIED", "SUPERSEDED").contains(receipt.getResultStatus()),
                "PLAN采购状态回执与PMS事件不一致");
        outbox.setStatus("DELIVERED").setNextAttemptTime(null).setDeliveredTime(LocalDateTime.now())
                .setPlanResultStatus(receipt.getResultStatus()).setPlanHandledTime(receipt.getHandledTime())
                .setPlanMessage(receipt.getMessage()).setLastErrorCode(null).setLastErrorMessage(null);
        require(outboxMapper.updateById(outbox) == 1, "PMS采购状态事件成功回执保存失败");
        operation(outbox, "DELIVERED:" + outbox.getEventId() + ':' + attempt, "DELIVERY_SUCCESS",
                "PROCESSING", "DELIVERED", attempt, "PLAN处理=" + receipt.getResultStatus()
                        + (Boolean.TRUE.equals(receipt.getDuplicate()) ? "，远端幂等重放" : "")
                        + "；" + StrUtil.blankToDefault(receipt.getMessage(), "无补充说明"));
        return true;
    }

    private void revalidateSource(PmsAgentPurchaseStatusOutboxEntity outbox) {
        PmsOrderEntity order = orderMapper.selectById(outbox.getPmsOrderId());
        if (order == null || !"AGENT_SHORTAGE".equals(order.getSourceType())
                || !Objects.equals(order.getFactoryCode(), outbox.getFactoryCode())
                || !Objects.equals(order.getAgentRequestId(), outbox.getAgentRequestId())
                || !Objects.equals(order.getAgentMrpId(), outbox.getMrpId())
                || !Objects.equals(order.getPmsOrderNo(), outbox.getPmsOrderNo())) {
            throw new NonRetryableDeliveryException("Agent采购订单来源不存在或归属已变化");
        }
        boolean rejectedEvent = "AGENT_PURCHASE_ORDER_REJECTED".equals(outbox.getEventType());
        boolean cancelledEvent = "AGENT_PURCHASE_ORDER_CANCELLED".equals(outbox.getEventType());
        if (!rejectedEvent && !cancelledEvent) {
            throw new NonRetryableDeliveryException("Agent采购状态事件类型无效");
        }
        if ((rejectedEvent && !"6".equals(order.getStatus()))
                || (cancelledEvent && !List.of("7", "8").contains(order.getStatus()))) {
            throw new SourceClosedException("采购订单已不处于事件声明的终止状态，原事件关闭");
        }
        List<PmsOrderDetailEntity> details = detailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getPmsOrderId, order.getId()).orderByAsc(PmsOrderDetailEntity::getId));
        require(details.size() == 1, "Agent采购订单行数量已经变化");
        PmsOrderDetailEntity detail = details.get(0);
        require(Objects.equals(detail.getId(), outbox.getPmsOrderDetailId())
                        && Objects.equals(detail.getPmsPlanNo(), outbox.getPmsPlanNo())
                        && Objects.equals(detail.getFactoryCode(), outbox.getFactoryCode())
                        && ((rejectedEvent && decimal(detail.getArrivalNum()) <= 0D)
                        || (cancelledEvent && decimal(detail.getCancelledNum()) > 0D
                        && detail.getCancellationRequestId() != null)),
                "采购订单行来源不一致或终止供给数量证据不完整");
        String currentReason = compact(rejectedEvent
                ? StrUtil.blankToDefault(order.getCheckReason(), "PMS正式审批驳回")
                : StrUtil.blankToDefault(order.getCancelReason(), "PMS采购订单取消未到货剩余量"), 1000);
        String currentFingerprint = hash(String.join("|", outbox.getEventId(), order.getAgentRequestId(),
                String.valueOf(order.getAgentMrpId()), String.valueOf(order.getId()), order.getPmsOrderNo(),
                String.valueOf(detail.getId()), detail.getPmsPlanNo(), order.getStatus(), currentReason,
                order.getFactoryCode()));
        require(Objects.equals(currentReason, outbox.getReason())
                        && Objects.equals(currentFingerprint, outbox.getSourceFingerprint()),
                "Agent采购订单终止供给来源事实已经变化");
    }

    private void operation(PmsAgentPurchaseStatusOutboxEntity outbox, String requestId, String type,
                           String before, String after, int attempt, String content) {
        require(operationMapper.insert(new PmsAgentPurchaseStatusOperationEntity().setOutboxId(outbox.getId())
                .setRequestId(requestId).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                .setAttemptNo(attempt).setContent(content).setOperatorCode("SYSTEM")
                .setOperatorName("PMS采购状态投递器").setOperationTime(LocalDateTime.now())
                .setFactoryCode(outbox.getFactoryCode())) == 1, "PMS采购状态事件履历保存失败");
    }

    private double decimal(Double value) {
        return value == null ? 0D : value;
    }

    private String compact(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new NonRetryableDeliveryException("PMS采购状态事件指纹生成失败");
        }
    }

    public static class NonRetryableDeliveryException extends RuntimeException {
        public NonRetryableDeliveryException(String message) {
            super(message);
        }
    }

    public static class SourceClosedException extends NonRetryableDeliveryException {
        public SourceClosedException(String message) {
            super(message);
        }
    }
}
