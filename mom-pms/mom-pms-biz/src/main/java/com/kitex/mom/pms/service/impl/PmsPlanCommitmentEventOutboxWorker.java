package com.kitex.mom.pms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.util.R;
import com.kitex.mom.plan.dto.PlanAutomationEventDTO;
import com.kitex.mom.plan.feign.RemotePlnAutomationEventService;
import com.kitex.mom.pms.entity.*;
import com.kitex.mom.pms.mapper.*;
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

/**
 * 单条PMS承诺事实独立投递；远端成功、本地提交失败时由PLAN永久幂等保证安全重放。
 */
@Service
@RequiredArgsConstructor
public class PmsPlanCommitmentEventOutboxWorker {
    private final PmsPlanCommitmentEventOutboxMapper outboxMapper;
    private final PmsPlanCommitmentEventOperationMapper operationMapper;
    private final PmsDeliveryCommitmentMapper commitmentMapper;
    private final PmsOrderChangeRequestMapper orderChangeRequestMapper;
    private final PmsOrderChangeLineMapper orderChangeLineMapper;
    private final PmsOrderMapper orderMapper;
    private final PmsOrderDetailMapper orderDetailMapper;
    private final PmsPlanMapper planMapper;
    private final RemotePlnAutomationEventService remoteEventService;

    private static void require(boolean condition, String message) {
        if (!condition) throw new NonRetryableDeliveryException(message);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Boolean deliverOne(Long outboxId) {
        PmsPlanCommitmentEventOutboxEntity outbox = outboxMapper.lockById(outboxId);
        if (outbox == null || !List.of("PENDING", "RETRY_WAIT").contains(outbox.getStatus())
                || outbox.getNextAttemptTime() == null || outbox.getNextAttemptTime().isAfter(LocalDateTime.now()))
            return false;
        require(outbox.getExpectedSupplyTime() != null && outbox.getExpectedSupplyTime().isAfter(LocalDateTime.now()),
                "预计齐料时刻缺失或已过期，必须补充新的可信预测");
        require(StrUtil.isNotBlank(outbox.getPayloadJson()) && StrUtil.isNotBlank(outbox.getPayloadFingerprint())
                        && Objects.equals(outbox.getPayloadFingerprint(), hash(outbox.getPayloadJson())),
                "PMS承诺事件载荷缺失或指纹不一致");
        revalidateSource(outbox);

        String before = outbox.getStatus();
        int attempt = Objects.requireNonNullElse(outbox.getAttemptCount(), 0) + 1;
        outbox.setStatus("PROCESSING").setAttemptCount(attempt).setLastAttemptTime(LocalDateTime.now());
        require(outboxMapper.updateById(outbox) == 1, "PMS承诺事件投递占用失败");
        operation(outbox, "DELIVER:" + outbox.getEventId() + ':' + attempt, "DELIVERY_START", before,
                "PROCESSING", attempt, "开始向PLAN制造事件中心投递供应商承诺变化事实");

        PlanAutomationEventDTO.ReceiveCommand command = new PlanAutomationEventDTO.ReceiveCommand();
        command.setEventId(outbox.getEventId());
        command.setSourceSystem("PMS");
        command.setEventType(outbox.getEventType());
        command.setEventVersion(outbox.getEventVersion());
        command.setAggregateType("PMS_PLAN");
        command.setAggregateId(outbox.getPmsPlanNo());
        command.setFactoryCode(outbox.getFactoryCode());
        command.setOccurredTime(outbox.getOccurredTime());
        command.setPriority(15);
        command.setPayloadJson(outbox.getPayloadJson());
        R<PlanAutomationEventDTO.ReceiveReceipt> response = remoteEventService.receive(command);
        if (response == null || !response.isOk() || response.getData() == null)
            throw new IllegalStateException(response == null ? "PLAN制造事件中心无响应" : response.getMessage());
        PlanAutomationEventDTO.ReceiveReceipt receipt = response.getData();
        require(Objects.equals(receipt.getEventId(), outbox.getEventId()), "PLAN收件回执事件ID不一致");
        outbox.setStatus("DELIVERED").setNextAttemptTime(null).setDeliveredTime(LocalDateTime.now())
                .setPlanInboxId(receipt.getInboxId()).setPlanEventNo(receipt.getEventNo())
                .setPlanEventStatus(receipt.getStatus()).setLastErrorCode(null).setLastErrorMessage(null);
        require(outboxMapper.updateById(outbox) == 1, "PMS承诺事件成功状态保存失败");
        operation(outbox, "DELIVERED:" + outbox.getEventId() + ':' + attempt, "DELIVERY_SUCCESS", "PROCESSING",
                "DELIVERED", attempt, "PLAN收件号=" + receipt.getEventNo() + "，状态=" + receipt.getStatus()
                        + (Boolean.TRUE.equals(receipt.getDuplicate()) ? "，远端幂等重放" : ""));
        return true;
    }

    private void revalidateSource(PmsPlanCommitmentEventOutboxEntity outbox) {
        if (List.of(PmsPlanCommitmentEventOutboxServiceImpl.ORDER_REMAINING_CANCELLED,
                PmsPlanCommitmentEventOutboxServiceImpl.ORDER_AMENDED).contains(outbox.getSourceType())) {
            revalidateOrderChange(outbox);
            return;
        }
        PmsDeliveryCommitmentEntity commitment = commitmentMapper.selectById(outbox.getCommitmentId());
        require(commitment != null && Objects.equals(commitment.getFactoryCode(), outbox.getFactoryCode())
                        && Objects.equals(commitment.getOrderDetailId(), outbox.getOrderDetailId())
                        && Objects.equals(commitment.getPmsPlanNo(), outbox.getPmsPlanNo())
                        && Objects.equals(commitment.getMatCode(), outbox.getMaterialCode())
                        && Objects.equals(commitment.getVersionNo(), outbox.getCommitmentVersion()),
                "PMS承诺来源不存在或结构化归属已变化");
        if (PmsPlanCommitmentEventOutboxServiceImpl.ACTIVATED.equals(outbox.getSourceType())
                && !"ACTIVE".equals(commitment.getStatus())) {
            throw new SourceClosedException("承诺激活事实已被新版本替代或取消");
        }
        require(PmsPlanCommitmentEventOutboxServiceImpl.ACTIVATED.equals(outbox.getSourceType())
                        || PmsPlanCommitmentEventOutboxServiceImpl.CANCELLED.equals(outbox.getSourceType())
                        && "CANCELLED".equals(commitment.getStatus()),
                "PMS承诺事件类型与当前权威状态不一致");
        List<PmsPlanEntity> plans = planMapper.selectList(new LambdaQueryWrapper<PmsPlanEntity>()
                .eq(PmsPlanEntity::getFactoryCode, outbox.getFactoryCode())
                .eq(PmsPlanEntity::getPmsPlanNo, outbox.getPmsPlanNo())
                .eq(PmsPlanEntity::getMatCode, outbox.getMaterialCode()));
        require(plans.size() == 1 && Objects.equals(plans.get(0).getOrderNo(), outbox.getPlanDemandNo()),
                "PMS采购计划到PLAN需求号的映射已变化");
    }

    private void revalidateOrderChange(PmsPlanCommitmentEventOutboxEntity outbox) {
        PmsOrderChangeRequestEntity request = orderChangeRequestMapper.selectById(outbox.getOrderChangeRequestId());
        PmsOrderChangeLineEntity line = orderChangeLineMapper.selectOne(
                new LambdaQueryWrapper<PmsOrderChangeLineEntity>()
                        .eq(PmsOrderChangeLineEntity::getChangeRequestId, outbox.getOrderChangeRequestId())
                        .eq(PmsOrderChangeLineEntity::getOrderDetailId, outbox.getOrderDetailId()));
        PmsOrderEntity order = orderMapper.selectById(outbox.getPmsOrderId());
        require(request != null && "SUCCEEDED".equals(request.getStatus()) && line != null && order != null
                        && Objects.equals(order.getLastChangeRequestId(), request.getId())
                        && Objects.equals(request.getPmsOrderId(), order.getId())
                        && Objects.equals(request.getFactoryCode(), outbox.getFactoryCode())
                        && Objects.equals(line.getPmsPlanNo(), outbox.getPmsPlanNo())
                        && Objects.equals(line.getMaterialCode(), outbox.getMaterialCode()),
                "采购订单变更来源不存在、未成功或结构化归属已变化");
        if (PmsPlanCommitmentEventOutboxServiceImpl.ORDER_REMAINING_CANCELLED.equals(outbox.getSourceType())) {
            require("CANCEL_REMAINING".equals(request.getChangeType())
                            && List.of("7", "8").contains(order.getStatus())
                            && line.getCancelledQuantity() != null && line.getCancelledQuantity().signum() > 0,
                    "采购订单取消来源类型、终态或取消数量已变化");
        } else {
            PmsOrderDetailEntity detail = orderDetailMapper.selectById(outbox.getOrderDetailId());
            require("AMEND_ORDER".equals(request.getChangeType())
                            && List.of("2", "3", "4").contains(order.getStatus())
                            && Objects.equals(order.getChangeVersion(), request.getChangeVersion())
                            && Boolean.TRUE.equals(line.getSupplyAffected()) && detail != null
                            && Objects.equals(detail.getPmsOrderId(), order.getId())
                            && Objects.equals(detail.getLastChangeRequestId(), request.getId())
                            && Objects.equals(detail.getChangeVersion(), request.getChangeVersion())
                            && detail.getOrderNum() != null && line.getTargetOrderQuantity() != null
                            && BigDecimal.valueOf(detail.getOrderNum()).compareTo(line.getTargetOrderQuantity()) == 0
                            && Objects.equals(detail.getDeliveryTime(), line.getTargetDeliveryDate())
                            && Objects.equals(detail.getPriceId(), line.getTargetPriceId())
                            && Objects.equals(detail.getMaterialAuthorizationId(), line.getTargetAuthorizationId()),
                    "采购订单通用变更版本或当前订单行事实已变化");
        }
        List<PmsPlanEntity> plans = planMapper.selectList(new LambdaQueryWrapper<PmsPlanEntity>()
                .eq(PmsPlanEntity::getFactoryCode, outbox.getFactoryCode())
                .eq(PmsPlanEntity::getPmsPlanNo, outbox.getPmsPlanNo())
                .eq(PmsPlanEntity::getMatCode, outbox.getMaterialCode()));
        require(plans.size() == 1 && Objects.equals(plans.get(0).getOrderNo(), outbox.getPlanDemandNo()),
                "采购订单变更事件对应采购计划到PLAN需求号映射已变化");
    }

    private void operation(PmsPlanCommitmentEventOutboxEntity outbox, String requestId, String type,
                           String before, String after, int attempt, String content) {
        require(operationMapper.insert(new PmsPlanCommitmentEventOperationEntity().setOutboxId(outbox.getId())
                .setRequestId(requestId).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                .setAttemptNo(attempt).setContent(content).setOperatorCode("SYSTEM")
                .setOperatorName("PMS承诺事件投递器").setOperationTime(LocalDateTime.now())
                .setFactoryCode(outbox.getFactoryCode())) == 1, "PMS承诺事件投递履历保存失败");
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

    public static class SourceClosedException extends NonRetryableDeliveryException {
        public SourceClosedException(String message) {
            super(message);
        }
    }
}
