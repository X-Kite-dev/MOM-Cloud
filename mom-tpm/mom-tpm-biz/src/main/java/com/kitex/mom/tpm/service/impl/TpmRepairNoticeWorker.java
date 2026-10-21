package com.kitex.mom.tpm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.tpm.entity.TpmRepairNoticeEntity;
import com.kitex.mom.tpm.entity.TpmRepairNoticeOperationEntity;
import com.kitex.mom.tpm.entity.TpmRepairTaskEntity;
import com.kitex.mom.tpm.mapper.TpmRepairNoticeMapper;
import com.kitex.mom.tpm.mapper.TpmRepairNoticeOperationMapper;
import com.kitex.mom.tpm.mapper.TpmRepairTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 外发前先提交占用；外发结果不明绝不自动重发。
 */
@Service
@RequiredArgsConstructor
public class TpmRepairNoticeWorker {
    private final TpmRepairNoticeMapper notices;
    private final TpmRepairNoticeOperationMapper operations;
    private final TpmRepairTaskMapper tasks;

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static String trim(String value) {
        return value == null || value.isBlank() ? "结果未确认" : value.substring(0, Math.min(value.length(), 900));
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new CheckedException(message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public TpmRepairNoticeEntity claim(Long id, Long tenantId, String factoryCode) {
        var candidate = notices.selectById(id);
        if (candidate == null || !Objects.equals(candidate.getTenantId(), tenantId) || !Objects.equals(candidate.getFactoryCode(), factoryCode)
                || !"PENDING".equals(candidate.getStatus()) || candidate.getDueTime().isAfter(LocalDateTime.now()))
            return null;
        // 与维修独立验证完成的任务→提醒加锁顺序保持一致，避免完成/超时升级相互死锁。
        var t = tasks.selectOne(new LambdaQueryWrapper<TpmRepairTaskEntity>().eq(TpmRepairTaskEntity::getId, candidate.getTaskId())
                .eq(TpmRepairTaskEntity::getTenantId, tenantId).eq(TpmRepairTaskEntity::getFactoryCode, factoryCode).last("FOR UPDATE"));
        var n = notices.lockOne(id, tenantId, factoryCode);
        if (n == null || !"PENDING".equals(n.getStatus()) || n.getDueTime().isAfter(LocalDateTime.now())) return null;
        if (t == null || !"0".equals(t.getState())) {
            n.setStatus("CANCELLED").setLastError("维修任务已完成或不存在，超时提醒取消");
            require(notices.updateById(n) == 1, "维修提醒取消失败");
            operation(n, "AUTO:" + id + ":CANCEL", "CANCEL", "PENDING", "CANCELLED", "任务已结束，不再发送提醒");
            return null;
        }
        int attempt = Objects.requireNonNullElse(n.getAttemptCount(), 0) + 1;
        n.setStatus("IN_FLIGHT").setAttemptCount(attempt).setClaimedTime(LocalDateTime.now()).setLastError(null);
        require(notices.updateById(n) == 1, "维修提醒占用失败");
        operation(n, "AUTO:" + id + ":" + attempt + ":CLAIM", "CLAIM", "PENDING", "IN_FLIGHT", "外发前已持久占用，等待消息回执");
        return n;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void finish(Long id, Long tenantId, String factoryCode, int attempt, boolean delivered, String detail) {
        var n = notices.lockOne(id, tenantId, factoryCode);
        if (n == null || !"IN_FLIGHT".equals(n.getStatus()) || !Objects.equals(n.getAttemptCount(), attempt)) return;
        String after = delivered ? "DELIVERED" : "UNCERTAIN";
        n.setStatus(after).setLastError(delivered ? null : trim(detail));
        if (delivered) n.setDeliveredTime(LocalDateTime.now());
        require(notices.updateById(n) == 1, "维修提醒投递结果保存失败");
        operation(n, "AUTO:" + id + ":" + attempt + ":" + after, after, "IN_FLIGHT", after,
                delivered ? "消息服务确认接收，包含系统及配置外部通道" : "消息服务未确认完整送达；禁止自动重试，须人工核对后处理：" + trim(detail));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markStale(Long id, Long tenantId, String factoryCode, LocalDateTime cutoff) {
        var n = notices.lockOne(id, tenantId, factoryCode);
        if (n == null || !"IN_FLIGHT".equals(n.getStatus()) || n.getClaimedTime() == null || n.getClaimedTime().isAfter(cutoff))
            return;
        n.setStatus("UNCERTAIN").setLastError("投递进程中断或消息回执未落库；须人工核对外部与站内信后处理");
        require(notices.updateById(n) == 1, "过期占用状态保存失败");
        operation(n, "AUTO:" + id + ":" + n.getAttemptCount() + ":STALE", "UNCERTAIN", "IN_FLIGHT", "UNCERTAIN", n.getLastError());
    }

    void operation(TpmRepairNoticeEntity n, String requestId, String action, String before, String after, String reason) {
        var op = new TpmRepairNoticeOperationEntity().setNoticeId(n.getId()).setRequestId(requestId)
                .setRequestFingerprint(hash(requestId)).setAction(action).setBeforeStatus(before).setAfterStatus(after)
                .setAttemptNo(n.getAttemptCount()).setReason(trim(reason)).setOperatorCode("SYSTEM")
                .setOperationTime(LocalDateTime.now()).setFactoryCode(n.getFactoryCode());
        op.setTenantId(n.getTenantId());
        require(operations.insert(op) == 1, "维修提醒投递履历保存失败");
    }
}
