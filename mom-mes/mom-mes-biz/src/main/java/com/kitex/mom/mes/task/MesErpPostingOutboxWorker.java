package com.kitex.mom.mes.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantBroker;
import com.kitex.mom.mes.entity.MesErpPostingOutboxEntity;
import com.kitex.mom.mes.mapper.MesErpPostingOutboxMapper;
import com.kitex.mom.plan.dto.PlnErpPostingEnvelope;
import com.kitex.mom.plan.feign.RemotePlanErpPostingService;
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
 * MES执行凭证向PLAN转交的跨租户工作器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MesErpPostingOutboxWorker {
    private static final int MAX_ATTEMPTS = 8;
    private final MesErpPostingOutboxMapper mapper;
    private final RemotePlanErpPostingService planPostingService;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
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
     * 转交失败只更新Outbox，不回滚原报工。
     */
    public int processDue(int limit) {
        int size = Math.max(1, Math.min(limit, 200));
        LocalDateTime now = LocalDateTime.now();
        List<MesErpPostingOutboxEntity> due = mapper.selectPage(new Page<>(1, size),
                new LambdaQueryWrapper<MesErpPostingOutboxEntity>()
                        .in(MesErpPostingOutboxEntity::getStatus, List.of("PENDING", "RETRY_WAIT"))
                        .and(q -> q.isNull(MesErpPostingOutboxEntity::getNextRetryTime)
                                .or().le(MesErpPostingOutboxEntity::getNextRetryTime, now))
                        .and(q -> q.isNull(MesErpPostingOutboxEntity::getLeaseUntil)
                                .or().le(MesErpPostingOutboxEntity::getLeaseUntil, now))
                        .orderByAsc(MesErpPostingOutboxEntity::getNextRetryTime)
                        .orderByAsc(MesErpPostingOutboxEntity::getId)).getRecords();
        int processed = 0;
        for (MesErpPostingOutboxEntity candidate : due) {
            try {
                Boolean result = TenantBroker.applyAs(candidate.getTenantId(), ignored -> inFactory(
                        candidate.getFactoryCode(), () -> processOne(candidate.getId())));
                if (Boolean.TRUE.equals(result)) processed++;
            } catch (RuntimeException ex) {
                log.warn("MES ERP凭证转交失败，outboxId={} tenantId={} factoryCode={} reason={}",
                        candidate.getId(), candidate.getTenantId(), candidate.getFactoryCode(), ex.getMessage());
            }
        }
        return processed;
    }

    private boolean processOne(Long id) {
        String lease = UUID.randomUUID().toString().replace("-", "");
        MesErpPostingOutboxEntity claimed = tx().execute(status -> claim(id, lease));
        if (claimed == null) return false;
        try {
            require(Objects.equals(sha256(claimed.getPayloadJson()), claimed.getRequestFingerprint()),
                    "MES ERP凭证不可变载荷指纹损坏");
            PlnErpPostingEnvelope envelope = objectMapper.readValue(claimed.getPayloadJson(), PlnErpPostingEnvelope.class);
            R<String> response = planPostingService.enqueue(envelope);
            require(response != null && response.isOk() && response.getData() != null,
                    response == null ? "PLAN ERP投递服务无响应" : response.getMsg());
            Long planOutboxId = Long.valueOf(response.getData());
            tx().executeWithoutResult(status -> delivered(claimed.getId(), lease, planOutboxId));
        } catch (Exception ex) {
            tx().executeWithoutResult(status -> failed(claimed.getId(), lease, message(ex)));
        }
        return true;
    }

    private MesErpPostingOutboxEntity claim(Long id, String lease) {
        MesErpPostingOutboxEntity row = mapper.selectOne(new LambdaQueryWrapper<MesErpPostingOutboxEntity>()
                .eq(MesErpPostingOutboxEntity::getId, id).last("FOR UPDATE"));
        if (row == null || !List.of("PENDING", "RETRY_WAIT").contains(row.getStatus())
                || row.getNextRetryTime() != null && row.getNextRetryTime().isAfter(LocalDateTime.now())
                || row.getLeaseUntil() != null && row.getLeaseUntil().isAfter(LocalDateTime.now())) return null;
        row.setStatus("TRANSFERRING").setAttemptCount(Objects.requireNonNullElse(row.getAttemptCount(), 0) + 1)
                .setLeaseToken(lease).setLeaseUntil(LocalDateTime.now().plusMinutes(5)).setLastError(null);
        require(mapper.updateById(row) == 1, "MES ERP凭证领取失败");
        return row;
    }

    private void delivered(Long id, String lease, Long planOutboxId) {
        MesErpPostingOutboxEntity row = locked(id, lease);
        row.setStatus("TRANSFERRED").setLeaseToken(null).setLeaseUntil(null).setNextRetryTime(null)
                .setPlanOutboxId(planOutboxId).setPlanStatus("PENDING").setTransferredTime(LocalDateTime.now());
        require(mapper.updateById(row) == 1, "MES ERP凭证转交回执保存失败");
    }

    private void failed(Long id, String lease, String error) {
        MesErpPostingOutboxEntity row = locked(id, lease);
        row.setLeaseToken(null).setLeaseUntil(null).setLastError(abbreviate(error));
        if (row.getAttemptCount() < MAX_ATTEMPTS) {
            row.setStatus("RETRY_WAIT").setNextRetryTime(LocalDateTime.now()
                    .plusMinutes(Math.min(240, 1L << Math.min(row.getAttemptCount() - 1, 7))));
        } else {
            row.setStatus("DEAD_LETTER").setNextRetryTime(null);
        }
        require(mapper.updateById(row) == 1, "MES ERP凭证失败状态保存失败");
    }

    private MesErpPostingOutboxEntity locked(Long id, String lease) {
        MesErpPostingOutboxEntity row = mapper.selectOne(new LambdaQueryWrapper<MesErpPostingOutboxEntity>()
                .eq(MesErpPostingOutboxEntity::getId, id)
                .eq(MesErpPostingOutboxEntity::getLeaseToken, lease).last("FOR UPDATE"));
        require(row != null && "TRANSFERRING".equals(row.getStatus()), "MES ERP凭证租约已失效");
        return row;
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
