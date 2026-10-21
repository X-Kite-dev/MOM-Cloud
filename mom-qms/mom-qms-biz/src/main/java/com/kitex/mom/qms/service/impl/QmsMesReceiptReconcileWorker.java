package com.kitex.mom.qms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.qms.entity.QmsMesReceiptReconcileEntity;
import com.kitex.mom.qms.mapper.QmsMesReceiptReconcileMapper;
import com.kitex.mom.qms.service.QmsBadInfoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

import static com.kitex.mom.qms.service.impl.QmsMesReceiptReconcileQueue.hash;
import static com.kitex.mom.qms.service.impl.QmsMesReceiptReconcileQueue.require;

@Service
@RequiredArgsConstructor
public class QmsMesReceiptReconcileWorker {
    private static final Set<String> ACTIVE = Set.of("PENDING", "WAIT_EXECUTION", "WAIT_VERIFY", "RETRY_WAIT");
    private final QmsMesReceiptReconcileMapper jobs;
    private final QmsMesReceiptReconcileQueue queue;
    private final QmsBadInfoService bad;

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean check(Long id, long revision) {
        var job = lock(id);
        if (job == null || job.getRevision() != revision || !ACTIVE.contains(job.getStatus()) || job.getNextCheckTime() == null || job.getNextCheckTime().isAfter(LocalDateTime.now()))
            return false;
        String before = job.getStatus();
        var result = bad.syncMesInBackground(job);
        String status = Objects.toString(result.get("status"), "");
        require(Set.of("WAIT_EXECUTION", "WAIT_VERIFY", "DONE", "SUPERSEDED").contains(status), "未知对账结果");
        String evidence = JSONUtil.toJsonStr(result);
        int applied = ((Number) result.get("applied")).intValue();
        job.setStatus(status).setRevision(revision + 1).setCheckCount(job.getCheckCount() + 1).setFailureCount(0).setLastError(null)
                .setLastCheckTime(LocalDateTime.now()).setLastResult(evidence)
                .setNextCheckTime(Set.of("DONE", "SUPERSEDED").contains(status) ? null : LocalDateTime.now().plusSeconds(60));
        if (result.get("receiptId") != null) job.setLastReceiptId(((Number) result.get("receiptId")).longValue());
        require(jobs.updateById(job) == 1, "对账进度保存失败");
        // 正常等待不无限追加相同履历；状态变化、实际恢复和错误恢复均永久留证。
        if (applied > 0 || !before.equals(status))
            queue.record(job, applied > 0 ? "RECOVERED" : "STATE_CHANGED", before, "CHECK:" + id + ":" + job.getRevision(), hash(evidence), evidence, null, "SYSTEM");
        return applied > 0;
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void failure(Long id, long revision, RuntimeException error) {
        var job = lock(id);
        if (job == null || job.getRevision() != revision || !ACTIVE.contains(job.getStatus())) return;
        String before = job.getStatus();
        int failures = job.getFailureCount() + 1;
        boolean needsAttention = error instanceof CheckedException || failures >= 8;
        // 不保存请求签名/密钥、HTTP响应正文或URL；日志只保留异常类型及受控业务错误。
        String message = error instanceof CheckedException ? StrUtil.maxLength(error.getMessage(), 500) : "远端访问或提交异常（" + error.getClass().getSimpleName() + "），执行状态未知，未重复实物动作";
        job.setRevision(revision + 1).setCheckCount(job.getCheckCount() + 1).setFailureCount(failures).setLastCheckTime(LocalDateTime.now())
                .setStatus(needsAttention ? "ATTENTION" : "RETRY_WAIT").setLastError(message)
                .setNextCheckTime(needsAttention ? null : LocalDateTime.now().plusSeconds(Math.min(3600, 30L * (1L << Math.min(failures, 7)))));
        require(jobs.updateById(job) == 1, "对账失败状态保存失败");
        queue.record(job, "CHECK_FAILED", before, "FAIL:" + id + ":" + job.getRevision(), hash(message), message, null, "SYSTEM");
    }

    private QmsMesReceiptReconcileEntity lock(Long id) {
        require(TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip() && StrUtil.isNotBlank(FactoryContextHolder.getFactoryCode()) && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()), "对账工作器缺少单一工厂上下文");
        return jobs.selectOne(new LambdaQueryWrapper<QmsMesReceiptReconcileEntity>().eq(QmsMesReceiptReconcileEntity::getId, id)
                .eq(QmsMesReceiptReconcileEntity::getTenantId, TenantContextHolder.getTenantId()).eq(QmsMesReceiptReconcileEntity::getFactoryCode, FactoryContextHolder.getFactoryCode()).last("FOR UPDATE"));
    }
}
