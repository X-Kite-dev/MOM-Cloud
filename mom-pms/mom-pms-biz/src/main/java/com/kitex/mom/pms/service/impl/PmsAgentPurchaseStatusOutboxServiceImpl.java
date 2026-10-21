package com.kitex.mom.pms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.pms.entity.PmsAgentPurchaseStatusOperationEntity;
import com.kitex.mom.pms.entity.PmsAgentPurchaseStatusOutboxEntity;
import com.kitex.mom.pms.entity.PmsOrderDetailEntity;
import com.kitex.mom.pms.entity.PmsOrderEntity;
import com.kitex.mom.pms.mapper.PmsAgentPurchaseStatusOperationMapper;
import com.kitex.mom.pms.mapper.PmsAgentPurchaseStatusOutboxMapper;
import com.kitex.mom.pms.mapper.PmsOrderDetailMapper;
import com.kitex.mom.pms.service.PmsAgentPurchaseStatusOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class PmsAgentPurchaseStatusOutboxServiceImpl implements PmsAgentPurchaseStatusOutboxService {
    private final PmsAgentPurchaseStatusOutboxMapper outboxMapper;
    private final PmsAgentPurchaseStatusOperationMapper operationMapper;
    private final PmsOrderDetailMapper detailMapper;
    private final PmsAgentPurchaseStatusOutboxWorker worker;
    private final PmsAgentPurchaseStatusOutboxFailureService failureService;

    private static void require(boolean condition, String message) {
        if (!condition) throw new CheckedException(message);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enqueueRejected(PmsOrderEntity order, String reason) {
        require(order != null && order.getId() != null && "AGENT_SHORTAGE".equals(order.getSourceType())
                        && "6".equals(order.getStatus()) && StrUtil.isNotBlank(order.getAgentRequestId())
                        && order.getAgentMrpId() != null && StrUtil.isNotBlank(order.getPmsOrderNo())
                        && StrUtil.isNotBlank(order.getFactoryCode()),
                "仅来源完整且已驳回的Agent采购订单允许发布状态事件");
        List<PmsOrderDetailEntity> details = detailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getPmsOrderId, order.getId()).orderByAsc(PmsOrderDetailEntity::getId));
        require(details.size() == 1, "Agent采购状态回写要求订单只有一条来源明确的采购行");
        PmsOrderDetailEntity detail = details.get(0);
        require(StrUtil.isNotBlank(detail.getPmsPlanNo()) && Objects.equals(detail.getFactoryCode(), order.getFactoryCode())
                        && value(detail.getArrivalNum()) <= 0D,
                "Agent采购订单行缺少采购计划映射、跨工厂或已发生到货");
        enqueue(order, detail, "AGENT_PURCHASE_ORDER_REJECTED", "REJECTED",
                StrUtil.blankToDefault(reason, "PMS正式审批驳回"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enqueueCancelled(PmsOrderEntity order, String reason) {
        require(order != null && order.getId() != null && "AGENT_SHORTAGE".equals(order.getSourceType())
                        && List.of("7", "8").contains(order.getStatus()) && StrUtil.isNotBlank(order.getAgentRequestId())
                        && order.getAgentMrpId() != null && StrUtil.isNotBlank(order.getPmsOrderNo())
                        && StrUtil.isNotBlank(order.getFactoryCode()),
                "仅来源完整且已取消剩余量的Agent采购订单允许发布状态事件");
        List<PmsOrderDetailEntity> details = detailMapper.selectList(new LambdaQueryWrapper<PmsOrderDetailEntity>()
                .eq(PmsOrderDetailEntity::getPmsOrderId, order.getId()).orderByAsc(PmsOrderDetailEntity::getId));
        require(details.size() == 1, "Agent采购状态回写要求订单只有一条来源明确的采购行");
        PmsOrderDetailEntity detail = details.get(0);
        require(StrUtil.isNotBlank(detail.getPmsPlanNo()) && Objects.equals(detail.getFactoryCode(), order.getFactoryCode())
                        && value(detail.getCancelledNum()) > 0D && detail.getCancellationRequestId() != null,
                "Agent采购取消订单行缺少采购计划映射、取消数量或变更申请");
        enqueue(order, detail, "AGENT_PURCHASE_ORDER_CANCELLED", "CANCELLED",
                StrUtil.blankToDefault(reason, "PMS采购订单取消未到货剩余量"));
    }

    private void enqueue(PmsOrderEntity order, PmsOrderDetailEntity detail, String eventType,
                         String eventSuffix, String reason) {
        String eventId = "PMS-AGENT-ORDER:" + order.getId() + ':' + eventSuffix;
        outboxMapper.lockEvent(eventId);
        String rejectionReason = compact(reason, 1000);
        LocalDateTime occurred = Objects.requireNonNullElse(order.getUpdateTime(), LocalDateTime.now())
                .truncatedTo(ChronoUnit.MILLIS);
        String sourceFingerprint = hash(String.join("|", eventId, order.getAgentRequestId(),
                String.valueOf(order.getAgentMrpId()), String.valueOf(order.getId()), order.getPmsOrderNo(),
                String.valueOf(detail.getId()), detail.getPmsPlanNo(), order.getStatus(), rejectionReason,
                order.getFactoryCode()));
        PmsAgentPurchaseStatusOutboxEntity existing = outboxMapper.selectOne(
                new LambdaQueryWrapper<PmsAgentPurchaseStatusOutboxEntity>()
                        .eq(PmsAgentPurchaseStatusOutboxEntity::getEventId, eventId));
        if (existing != null) {
            require(Objects.equals(existing.getSourceFingerprint(), sourceFingerprint),
                    "同一Agent采购状态事件号的来源事实已变化，禁止覆盖");
            return;
        }
        PmsAgentPurchaseStatusOutboxEntity outbox = new PmsAgentPurchaseStatusOutboxEntity()
                .setEventId(eventId).setEventType(eventType)
                .setSourceFingerprint(sourceFingerprint).setAgentRequestId(order.getAgentRequestId())
                .setMrpId(order.getAgentMrpId()).setPmsOrderId(order.getId()).setPmsOrderNo(order.getPmsOrderNo())
                .setPmsOrderDetailId(detail.getId()).setPmsPlanNo(detail.getPmsPlanNo())
                .setOrderStatus(order.getStatus()).setReason(rejectionReason).setOccurredTime(occurred)
                .setStatus("PENDING").setAttemptCount(0).setMaxAttempts(8).setNextAttemptTime(LocalDateTime.now())
                .setFactoryCode(order.getFactoryCode());
        require(outboxMapper.insert(outbox) == 1, "Agent采购状态事件Outbox保存失败");
        operation(outbox, "CREATE:" + eventId, "OUTBOX_CREATED", null, "PENDING", 0,
                ("AGENT_PURCHASE_ORDER_REJECTED".equals(eventType) ? "采购订单驳回" : "采购订单剩余量取消")
                        + "已与PMS事务同箱；等待释放PLAN MRP有效供应占用",
                "SYSTEM", "PMS采购状态事件");
    }

    @Override
    public int dispatchDue(int limit) {
        int batchSize = Math.max(1, Math.min(limit, 200));
        List<PmsAgentPurchaseStatusOutboxEntity> due = outboxMapper.selectPage(new Page<>(1, batchSize),
                new LambdaQueryWrapper<PmsAgentPurchaseStatusOutboxEntity>()
                        .in(PmsAgentPurchaseStatusOutboxEntity::getStatus, "PENDING", "RETRY_WAIT")
                        .le(PmsAgentPurchaseStatusOutboxEntity::getNextAttemptTime, LocalDateTime.now())
                        .orderByAsc(PmsAgentPurchaseStatusOutboxEntity::getNextAttemptTime)
                        .orderByAsc(PmsAgentPurchaseStatusOutboxEntity::getId)).getRecords();
        int delivered = 0;
        for (PmsAgentPurchaseStatusOutboxEntity item : due) {
            try {
                boolean done = item.getTenantId() == null ? dispatchOne(item.getId())
                        : TenantBroker.applyAs(item.getTenantId(), ignored -> dispatchOne(item.getId()));
                if (done) delivered++;
            } catch (RuntimeException exception) {
                log.error("PMS Agent采购状态事件调度异常，eventId={}，tenantId={}",
                        item.getEventId(), item.getTenantId(), exception);
            }
        }
        return delivered;
    }

    private boolean dispatchOne(Long id) {
        try {
            return Boolean.TRUE.equals(worker.deliverOne(id));
        } catch (RuntimeException exception) {
            boolean sourceClosed = exception instanceof PmsAgentPurchaseStatusOutboxWorker.SourceClosedException;
            failureService.markFailure(id, exception,
                    !(exception instanceof PmsAgentPurchaseStatusOutboxWorker.NonRetryableDeliveryException), sourceClosed);
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PmsAgentPurchaseStatusOutboxEntity retry(Long outboxId, String requestId, String reason) {
        require(outboxId != null && StrUtil.isNotBlank(requestId) && StrUtil.isNotBlank(reason),
                "采购状态事件重试必须提供事件、请求号和原因");
        require(requestId.trim().length() <= 128 && reason.trim().length() <= 500,
                "采购状态事件重试请求号或原因过长");
        PmsAgentPurchaseStatusOutboxEntity outbox = outboxMapper.lockById(outboxId);
        require(outbox != null, "采购状态事件不存在");
        PmsAgentPurchaseStatusOperationEntity duplicate = operationMapper.selectOne(
                new LambdaQueryWrapper<PmsAgentPurchaseStatusOperationEntity>()
                        .eq(PmsAgentPurchaseStatusOperationEntity::getRequestId, requestId.trim()));
        if (duplicate != null) {
            require(Objects.equals(duplicate.getOutboxId(), outboxId)
                            && "MANUAL_RETRY".equals(duplicate.getOperationType()),
                    "采购状态事件重试幂等号已被其他操作使用");
            return outbox;
        }
        require(List.of("RETRY_WAIT", "DEAD_LETTER").contains(outbox.getStatus()),
                "仅退避或死信采购状态事件允许人工重试");
        String before = outbox.getStatus();
        MomUser user = SecurityUtils.getUser();
        outbox.setStatus("PENDING").setAttemptCount(0).setNextAttemptTime(LocalDateTime.now())
                .setLastErrorCode(null).setLastErrorMessage(null).setManualRequestId(requestId.trim())
                .setManualReason(reason.trim()).setManualOperatorCode(user == null ? "UNKNOWN" : user.getUsername())
                .setManualOperatorName(user == null ? "未知用户" : StrUtil.blankToDefault(user.getName(), user.getUsername()))
                .setManualOperationTime(LocalDateTime.now());
        require(outboxMapper.updateById(outbox) == 1, "采购状态事件人工重试保存失败");
        operation(outbox, requestId.trim(), "MANUAL_RETRY", before, "PENDING", 0, reason.trim(),
                outbox.getManualOperatorCode(), outbox.getManualOperatorName());
        return outbox;
    }

    @Override
    public PmsAgentPurchaseStatusOutboxEntity findByOrderId(Long orderId) {
        require(orderId != null, "采购订单不能为空");
        return outboxMapper.selectOne(new LambdaQueryWrapper<PmsAgentPurchaseStatusOutboxEntity>()
                .eq(PmsAgentPurchaseStatusOutboxEntity::getPmsOrderId, orderId)
                .orderByDesc(PmsAgentPurchaseStatusOutboxEntity::getId).last("LIMIT 1"));
    }

    @Override
    public List<PmsAgentPurchaseStatusOperationEntity> operations(Long outboxId) {
        require(outboxId != null, "采购状态事件不能为空");
        require(outboxMapper.selectById(outboxId) != null, "采购状态事件不存在");
        return operationMapper.selectList(new LambdaQueryWrapper<PmsAgentPurchaseStatusOperationEntity>()
                .eq(PmsAgentPurchaseStatusOperationEntity::getOutboxId, outboxId)
                .orderByAsc(PmsAgentPurchaseStatusOperationEntity::getOperationTime)
                .orderByAsc(PmsAgentPurchaseStatusOperationEntity::getId));
    }

    private void operation(PmsAgentPurchaseStatusOutboxEntity outbox, String requestId, String type,
                           String before, String after, int attempt, String content,
                           String operatorCode, String operatorName) {
        require(operationMapper.insert(new PmsAgentPurchaseStatusOperationEntity().setOutboxId(outbox.getId())
                        .setRequestId(requestId).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                        .setAttemptNo(attempt).setContent(content).setOperatorCode(operatorCode).setOperatorName(operatorName)
                        .setOperationTime(LocalDateTime.now()).setFactoryCode(outbox.getFactoryCode())) == 1,
                "采购状态事件操作履历保存失败");
    }

    private double value(Double value) {
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
            throw new CheckedException("PMS采购状态事件指纹生成失败");
        }
    }
}
