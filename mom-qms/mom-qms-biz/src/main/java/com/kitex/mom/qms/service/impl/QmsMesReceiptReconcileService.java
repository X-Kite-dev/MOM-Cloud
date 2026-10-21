package com.kitex.mom.qms.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mdm.dto.MdmFactoryAccessCheck;
import com.kitex.mom.mdm.feign.RemoteMdmFactoryAccessService;
import com.kitex.mom.qms.entity.QmsMesReceiptReconcileEntity;
import com.kitex.mom.qms.entity.QmsMesReceiptReconcileOperationEntity;
import com.kitex.mom.qms.mapper.QmsMesReceiptReconcileMapper;
import com.kitex.mom.qms.mapper.QmsMesReceiptReconcileOperationMapper;
import com.kitex.mom.qms.service.QmsBadInfoService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.kitex.mom.qms.service.impl.QmsMesReceiptReconcileQueue.hash;
import static com.kitex.mom.qms.service.impl.QmsMesReceiptReconcileQueue.require;

@Service
@RequiredArgsConstructor
@Slf4j
public class QmsMesReceiptReconcileService {
    private final QmsMesReceiptReconcileMapper jobs;
    private final QmsMesReceiptReconcileOperationMapper operations;
    private final QmsMesReceiptReconcileQueue queue;
    private final QmsMesReceiptReconcileWorker worker;
    private final QmsMesReceiptDiscoveryService discovery;
    private final QmsBadInfoService bad;
    private final RemoteMdmFactoryAccessService factories;
    @Value("${mom.quality-receipt-reconcile.enabled:${MOM_MES_RECEIPT_RECONCILE_ENABLED:false}}")
    private boolean enabled;
    @Value("${mom.quality-receipt-reconcile.read-secret:${MOM_MES_RECEIPT_READ_SECRET:}}")
    private String secret;

    public Map<String, Object> page(long page, String status, String ncrNo) {
        String factory = access("read");
        require(page > 0 && page <= 100000, "页码无效");
        require(StrUtil.isBlank(status) || Set.of("PENDING", "WAIT_EXECUTION", "WAIT_VERIFY", "RETRY_WAIT", "ATTENTION", "DONE", "SUPERSEDED").contains(status), "对账状态无效");
        var records = jobs.selectPage(new Page<>(page, 30), scope(factory).eq(StrUtil.isNotBlank(status), QmsMesReceiptReconcileEntity::getStatus, status)
                .like(StrUtil.isNotBlank(ncrNo), QmsMesReceiptReconcileEntity::getNcrNo, ncrNo).orderByDesc(QmsMesReceiptReconcileEntity::getId));
        return Map.of("records", records, "enabled", enabled, "credentialConfigured", secret != null && secret.length() >= 32,
                "notice", "调度需配置 qms_mes_receipt_reconcile；开关开启不代表调度任务已实际运行，请核对最后检查时间。人工同步仍使用实名原入口。");
    }

    public Map<String, Object> detail(Long id, long page) {
        String factory = access("read");
        require(page > 0 && page <= 100000, "页码无效");
        var job = jobs.selectOne(scope(factory).eq(QmsMesReceiptReconcileEntity::getId, id));
        require(job != null, "当前工厂对账记录不存在");
        var history = operations.selectPage(new Page<>(page, 30), new LambdaQueryWrapper<QmsMesReceiptReconcileOperationEntity>()
                .eq(QmsMesReceiptReconcileOperationEntity::getReconcileId, id).eq(QmsMesReceiptReconcileOperationEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(QmsMesReceiptReconcileOperationEntity::getFactoryCode, factory).orderByDesc(QmsMesReceiptReconcileOperationEntity::getRevision));
        return Map.of("job", job, "operations", history);
    }

    @Transactional(rollbackFor = Exception.class)
    public void retry(Retry c) {
        String factory = access("write");
        var actor = SecurityUtils.getUser();
        require(c != null && c.getId() != null && c.getRevision() != null && c.getRequestId() != null && c.getRequestId().matches("[A-Za-z0-9_-]{16,64}") && StrUtil.isNotBlank(c.getReason()) && c.getReason().length() <= 500, "请提供记录、版本、请求号和500字以内重查理由");
        c.setReason(c.getReason().trim());
        String fingerprint = hash(JSONUtil.toJsonStr(Arrays.asList(c.getId(), c.getRevision(), c.getReason(), actor.getId())));
        jobs.lockKey("QMS-MES-RETRY:" + TenantContextHolder.getTenantId() + ":" + factory + ":" + c.getRequestId());
        var old = operations.selectOne(new LambdaQueryWrapper<QmsMesReceiptReconcileOperationEntity>().eq(QmsMesReceiptReconcileOperationEntity::getRequestId, c.getRequestId())
                .eq(QmsMesReceiptReconcileOperationEntity::getTenantId, TenantContextHolder.getTenantId()).eq(QmsMesReceiptReconcileOperationEntity::getFactoryCode, factory));
        if (old != null) {
            require(fingerprint.equals(old.getFingerprint()), "请求号已用于不同人员或内容");
            return;
        }
        require(enabled && secret != null && secret.length() >= 32, "自动对账开关或服务只读凭据未就绪；可使用原人工同步入口核对，不能排队冒充已恢复");
        var job = jobs.selectOne(scope(factory).eq(QmsMesReceiptReconcileEntity::getId, c.getId()).last("FOR UPDATE"));
        require(job != null && Objects.equals(job.getRevision(), c.getRevision()) && Set.of("ATTENTION", "RETRY_WAIT").contains(job.getStatus()), "仅最新失败/待核对记录可重新排队；请先刷新");
        String before = job.getStatus();
        job.setStatus("RETRY_WAIT").setRevision(job.getRevision() + 1).setFailureCount(0).setLastError(null).setNextCheckTime(LocalDateTime.now());
        require(jobs.updateById(job) == 1, "重新排队失败");
        queue.record(job, "MANUAL_RETRY", before, c.getRequestId(), fingerprint, c.getReason(), actor.getId(), actor.getUsername());
    }

    public Map<String, Integer> scan() {
        require(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() == null && org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() == null, "跨租户对账只允许独立调度，不接受用户请求上下文");
        if (!enabled) return Map.of("disabled", 1, "recovered", 0);
        require(secret != null && secret.length() >= 32, "自动对账只读凭据未就绪");
        int discovered = 0, recovered = 0, failed = 0;
        for (var candidate : discovery.next())
            try {
                inScope(candidate.getTenantId(), candidate.getFactoryCode(), () -> {
                    bad.discoverMesReconcile(candidate.getId());
                    return false;
                });
                discovered++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("MES回执历史待办发现失败，detailId={}，类型={}", candidate.getId(), e.getClass().getSimpleName());
            }
        for (var candidate : jobs.due(50)) {
            try {
                if (inScope(candidate.getTenantId(), candidate.getFactoryCode(), () -> worker.check(candidate.getId(), candidate.getRevision())))
                    recovered++;
            } catch (RuntimeException e) {
                failed++;
                try {
                    inScope(candidate.getTenantId(), candidate.getFactoryCode(), () -> {
                        worker.failure(candidate.getId(), candidate.getRevision(), e);
                        return false;
                    });
                } catch (RuntimeException recordError) {
                    log.error("MES回执对账失败记录未提交，id={}，类型={}", candidate.getId(), recordError.getClass().getSimpleName());
                }
            }
        }
        return Map.of("discovered", discovered, "recovered", recovered, "failed", failed);
    }

    private boolean inScope(Long tenant, String factory, java.util.function.Supplier<Boolean> work) {
        require(tenant != null && StrUtil.isNotBlank(factory), "待办缺少租户或工厂");
        Long oldTenant = TenantContextHolder.getTenantId();
        boolean oldSkip = TenantContextHolder.getTenantSkip();
        String oldFactory = FactoryContextHolder.getFactoryCode();
        boolean oldFactorySkip = Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
        try {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
            TenantContextHolder.setTenantId(tenant);
            FactoryContextHolder.setFactoryCode(factory);
            return work.get();
        } finally {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
            TenantContextHolder.setTenantId(oldTenant);
            if (oldSkip) TenantContextHolder.setTenantSkip();
            FactoryContextHolder.setFactoryCode(oldFactory);
            if (oldFactorySkip) FactoryContextHolder.setFactorySkip();
        }
    }

    private String access(String permission) {
        var actor = SecurityUtils.getUser();
        String factory = FactoryContextHolder.getFactoryCode();
        require(actor != null && actor.getId() != null && TenantContextHolder.getTenantId() != null && !TenantContextHolder.getTenantSkip() && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()) && StrUtil.isNotBlank(factory), "请实名登录并选择实际工厂");
        var response = factories.check(new MdmFactoryAccessCheck().setUserId(actor.getId()).setFactoryCode(factory).setRequiredPermission(permission));
        require(response != null && response.isOk() && Boolean.TRUE.equals(response.getData()), "当前工厂无访问权限或MDM不可核验");
        return factory;
    }

    private LambdaQueryWrapper<QmsMesReceiptReconcileEntity> scope(String factory) {
        return new LambdaQueryWrapper<QmsMesReceiptReconcileEntity>().eq(QmsMesReceiptReconcileEntity::getTenantId, TenantContextHolder.getTenantId()).eq(QmsMesReceiptReconcileEntity::getFactoryCode, factory);
    }

    @Data
    public static class Retry {
        private Long id;
        private Long revision;
        private String requestId;
        private String reason;
    }
}
