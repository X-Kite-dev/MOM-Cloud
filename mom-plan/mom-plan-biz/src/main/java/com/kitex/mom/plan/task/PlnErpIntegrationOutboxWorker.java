package com.kitex.mom.plan.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.plan.dto.PlnErpPostingEnvelope;
import com.kitex.mom.plan.entity.PlnErpIntegrationOperationEntity;
import com.kitex.mom.plan.entity.PlnErpIntegrationOutboxEntity;
import com.kitex.mom.plan.integration.ErpPlanningConnector;
import com.kitex.mom.plan.integration.ErpPlanningConnectorRegistry;
import com.kitex.mom.plan.mapper.PlnErpIntegrationOperationMapper;
import com.kitex.mom.plan.mapper.PlnErpIntegrationOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * ERP Outbox 的跨租户领取、连接器投递、退避和死信工作器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlnErpIntegrationOutboxWorker {
    private static final int MAX_ATTEMPTS = 8;
    private final PlnErpIntegrationOutboxMapper outboxMapper;
    private final PlnErpIntegrationOperationMapper operationMapper;
    private final ErpPlanningConnectorRegistry connectorRegistry;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private static String abbreviate(String value) {
        return value == null ? null : value.substring(0, Math.min(value.length(), 1000));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    /**
     * 处理到期投递；多实例依赖数据库租约避免重复发送。
     */
    public int processDue(int limit) {
        int size = Math.max(1, Math.min(limit, 200));
        LocalDateTime now = LocalDateTime.now();
        List<PlnErpIntegrationOutboxEntity> due = outboxMapper.selectPage(new Page<>(1, size),
                new LambdaQueryWrapper<PlnErpIntegrationOutboxEntity>()
                        .in(PlnErpIntegrationOutboxEntity::getStatus, List.of("PENDING", "RETRY_WAIT"))
                        .and(q -> q.isNull(PlnErpIntegrationOutboxEntity::getNextRetryTime)
                                .or().le(PlnErpIntegrationOutboxEntity::getNextRetryTime, now))
                        .and(q -> q.isNull(PlnErpIntegrationOutboxEntity::getLeaseUntil)
                                .or().le(PlnErpIntegrationOutboxEntity::getLeaseUntil, now))
                        .orderByAsc(PlnErpIntegrationOutboxEntity::getNextRetryTime)
                        .orderByAsc(PlnErpIntegrationOutboxEntity::getId)).getRecords();
        int processed = 0;
        for (PlnErpIntegrationOutboxEntity candidate : due) {
            try {
                Boolean done = TenantBroker.applyAs(candidate.getTenantId(), ignored -> inFactory(
                        candidate.getFactoryCode(), () -> processOne(candidate.getId())));
                if (Boolean.TRUE.equals(done)) processed++;
            } catch (RuntimeException ex) {
                log.warn("ERP投递处理失败，outboxId={} tenantId={} factoryCode={} reason={}",
                        candidate.getId(), candidate.getTenantId(), candidate.getFactoryCode(), ex.getMessage());
            }
        }
        return processed;
    }

    private boolean processOne(Long id) {
        String lease = UUID.randomUUID().toString().replace("-", "");
        PlnErpIntegrationOutboxEntity claimed = tx().execute(status -> claim(id, lease));
        if (claimed == null) return false;
        ErpPlanningConnector connector = connectorRegistry.find(claimed.getErpSystemCode()).orElse(null);
        if (connector == null) {
            tx().executeWithoutResult(status -> finishBlocked(claimed, lease));
            return true;
        }
        ErpPlanningConnector.SendResult result;
        try {
            require(Objects.equals(sha256(claimed.getPayloadJson()), claimed.getRequestFingerprint()),
                    "ERP投递不可变载荷指纹损坏");
            PlnErpPostingEnvelope envelope = objectMapper.readValue(claimed.getPayloadJson(), PlnErpPostingEnvelope.class);
            result = connector.send(envelope);
            require(result != null, "ERP连接器没有返回明确回执");
        } catch (Exception ex) {
            result = ErpPlanningConnector.SendResult.retryable(message(ex));
        }
        ErpPlanningConnector.SendResult finalResult = result;
        tx().executeWithoutResult(status -> finish(claimed, lease, finalResult));
        return true;
    }

    private PlnErpIntegrationOutboxEntity claim(Long id, String lease) {
        PlnErpIntegrationOutboxEntity row = outboxMapper.selectOne(new LambdaQueryWrapper<PlnErpIntegrationOutboxEntity>()
                .eq(PlnErpIntegrationOutboxEntity::getId, id).last("FOR UPDATE"));
        if (row == null || !List.of("PENDING", "RETRY_WAIT").contains(row.getStatus())
                || row.getNextRetryTime() != null && row.getNextRetryTime().isAfter(LocalDateTime.now())
                || row.getLeaseUntil() != null && row.getLeaseUntil().isAfter(LocalDateTime.now())) return null;
        String before = row.getStatus();
        row.setStatus("SENDING").setAttemptCount(Objects.requireNonNullElse(row.getAttemptCount(), 0) + 1)
                .setLeaseToken(lease).setLeaseUntil(LocalDateTime.now().plusMinutes(5))
                .setSentTime(LocalDateTime.now()).setLastError(null);
        require(outboxMapper.updateById(row) == 1, "ERP投递任务领取失败");
        operation(row, "SEND_START", before, "SENDING", "工作器已领取投递租约", "SYSTEM");
        return row;
    }

    private void finishBlocked(PlnErpIntegrationOutboxEntity claimed, String lease) {
        PlnErpIntegrationOutboxEntity row = locked(claimed.getId(), lease);
        row.setStatus("BLOCKED_CONNECTOR").setLeaseToken(null).setLeaseUntil(null).setNextRetryTime(null)
                .setLastError("未安装ERP适配器：" + claimed.getErpSystemCode());
        require(outboxMapper.updateById(row) == 1, "ERP连接器阻断状态保存失败");
        operation(row, "CONNECTOR_BLOCK", "SENDING", row.getStatus(), row.getLastError(), "SYSTEM");
    }

    private void finish(PlnErpIntegrationOutboxEntity claimed, String lease,
                        ErpPlanningConnector.SendResult result) {
        PlnErpIntegrationOutboxEntity row = locked(claimed.getId(), lease);
        row.setLeaseToken(null).setLeaseUntil(null).setReceiptCode(result.receiptCode())
                .setReceiptPayload(result.receiptPayload())
                .setReceiptFingerprint(result.receiptPayload() == null ? null : sha256(result.receiptPayload()))
                .setLastError(result.accepted() ? null : abbreviate(result.message()));
        if (result.accepted()) {
            row.setStatus("SUCCEEDED").setCompletedTime(LocalDateTime.now()).setNextRetryTime(null);
        } else if (!result.retryable()) {
            row.setStatus("BUSINESS_REJECTED").setNextRetryTime(null).setDeadLetterReason(result.message());
        } else if (row.getAttemptCount() < MAX_ATTEMPTS) {
            row.setStatus("RETRY_WAIT").setNextRetryTime(LocalDateTime.now()
                    .plusMinutes(Math.min(240, 1L << Math.min(row.getAttemptCount() - 1, 7))));
        } else {
            row.setStatus("DEAD_LETTER").setNextRetryTime(null).setDeadLetterReason(result.message());
        }
        require(outboxMapper.updateById(row) == 1, "ERP投递回执保存失败");
        operation(row, result.accepted() ? "ACK" : "SEND_RESULT", "SENDING", row.getStatus(),
                result.message(), "SYSTEM");
    }

    private PlnErpIntegrationOutboxEntity locked(Long id, String lease) {
        PlnErpIntegrationOutboxEntity row = outboxMapper.selectOne(new LambdaQueryWrapper<PlnErpIntegrationOutboxEntity>()
                .eq(PlnErpIntegrationOutboxEntity::getId, id)
                .eq(PlnErpIntegrationOutboxEntity::getLeaseToken, lease).last("FOR UPDATE"));
        require(row != null && "SENDING".equals(row.getStatus()), "ERP投递租约已失效");
        return row;
    }

    private void operation(PlnErpIntegrationOutboxEntity row, String type, String before,
                           String after, String reason, String operator) {
        PlnErpIntegrationOperationEntity op = new PlnErpIntegrationOperationEntity()
                .setOutboxId(row.getId()).setOperationType(type).setBeforeStatus(before).setAfterStatus(after)
                .setRequestFingerprint(row.getRequestFingerprint()).setReason(abbreviate(reason))
                .setOperatorCode(operator).setOperatorName(operator).setOperationTime(LocalDateTime.now())
                .setFactoryCode(row.getFactoryCode());
        op.setId(cn.hutool.core.util.IdUtil.getSnowflakeNextId());
        op.setTenantId(row.getTenantId());
        op.setCreateBy(operator);
        require(operationMapper.insert(op) == 1, "ERP投递操作履历保存失败");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private <T> T inFactory(String factory, Supplier<T> work) {
        String old = FactoryContextHolder.getFactoryCode();
        boolean skip = Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
        try {
            FactoryContextHolder.clear();
            FactoryContextHolder.setFactoryCode(factory);
            return work.get();
        } finally {
            FactoryContextHolder.clear();
            FactoryContextHolder.setFactoryCode(old);
            if (skip) FactoryContextHolder.setFactorySkip();
        }
    }
}
