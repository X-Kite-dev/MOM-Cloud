package com.kitex.mom.mdm.task;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.dto.MdmFactoryPlanningImpactDTO;
import com.kitex.mom.mdm.entity.MdmFactoryPlanningPolicyEntity;
import com.kitex.mom.mdm.mapper.MdmFactoryPlanningPolicyMapper;
import com.kitex.mom.mdm.service.FactoryPlanningPolicyImpactInspector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 到点激活预约计划协同策略；每个工厂独立事务，失败不替换旧生效版本。
 */
@Service
@RequiredArgsConstructor
public class MdmFactoryPlanningPolicyActivationWorker {

    private final MdmFactoryPlanningPolicyMapper policyMapper;
    private final FactoryPlanningPolicyImpactInspector impactInspector;

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * 跨租户扫描已到生效时间的预约版本，仅返回身份，不在跳过隔离时做业务更新。
     */
    public List<MdmFactoryPlanningPolicyEntity> discoverDue(int limit) {
        int size = Math.max(1, Math.min(limit, 200));
        return withSkippedScope(() -> policyMapper.selectList(new LambdaQueryWrapper<MdmFactoryPlanningPolicyEntity>()
                .eq(MdmFactoryPlanningPolicyEntity::getStatus, "PENDING_EFFECTIVE")
                .eq(MdmFactoryPlanningPolicyEntity::getDelFlag, "0")
                .le(MdmFactoryPlanningPolicyEntity::getEffectiveFrom, LocalDateTime.now())
                .orderByAsc(MdmFactoryPlanningPolicyEntity::getEffectiveFrom)
                .last("LIMIT " + size)));
    }

    /**
     * 在真实租户与工厂上下文中重新检查事实并原子切换。
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public boolean activateOne(Long policyId, Long tenantId, String factoryCode) {
        return inScope(tenantId, factoryCode, () -> {
            policyMapper.lock("MDM_FACTORY_PLANNING_POLICY:" + tenantId + ':' + factoryCode);
            MdmFactoryPlanningPolicyEntity pending = policyMapper.selectOne(scope(tenantId, factoryCode)
                    .eq(MdmFactoryPlanningPolicyEntity::getId, policyId)
                    .eq(MdmFactoryPlanningPolicyEntity::getStatus, "PENDING_EFFECTIVE")
                    .last("FOR UPDATE"));
            if (pending == null || pending.getEffectiveFrom().isAfter(LocalDateTime.now())) {
                return false;
            }
            if (!Objects.equals(pending.getContentFingerprint(), fingerprint(pending))) {
                fail(pending, "预约策略内容摘要损坏");
                return false;
            }
            MdmFactoryPlanningImpactDTO impact = impactInspector.inspect(pending);
            if (!Boolean.TRUE.equals(impact.getImmediateSwitchAllowed())) {
                fail(pending, "到点复核仍存在阻断：" + String.join("、", impact.getBlockers()));
                return false;
            }
            MdmFactoryPlanningPolicyEntity active = policyMapper.selectOne(scope(tenantId, factoryCode)
                    .eq(MdmFactoryPlanningPolicyEntity::getStatus, "ACTIVE").last("FOR UPDATE"));
            if (active != null) {
                int retired = policyMapper.update(null, new LambdaUpdateWrapper<MdmFactoryPlanningPolicyEntity>()
                        .eq(MdmFactoryPlanningPolicyEntity::getId, active.getId())
                        .eq(MdmFactoryPlanningPolicyEntity::getStatus, "ACTIVE")
                        .set(MdmFactoryPlanningPolicyEntity::getStatus, "RETIRED")
                        .set(MdmFactoryPlanningPolicyEntity::getUpdateBy, "planning-policy-scheduler")
                        .set(MdmFactoryPlanningPolicyEntity::getUpdateTime, LocalDateTime.now()));
                if (retired != 1) {
                    throw new IllegalStateException("旧计划协同策略退役失败");
                }
            }
            pending.setStatus("ACTIVE");
            pending.setActivationFailureReason(null);
            pending.setUpdateBy("planning-policy-scheduler");
            pending.setUpdateTime(LocalDateTime.now());
            if (policyMapper.updateById(pending) != 1) {
                throw new IllegalStateException("预约计划协同策略激活失败");
            }
            return true;
        });
    }

    private void fail(MdmFactoryPlanningPolicyEntity pending, String reason) {
        pending.setStatus("ACTIVATION_FAILED");
        pending.setActivationFailureReason(StrUtil.maxLength(reason, 1000));
        pending.setUpdateBy("planning-policy-scheduler");
        pending.setUpdateTime(LocalDateTime.now());
        if (policyMapper.updateById(pending) != 1) {
            throw new IllegalStateException("预约策略失败原因保存失败");
        }
    }

    private LambdaQueryWrapper<MdmFactoryPlanningPolicyEntity> scope(Long tenantId, String factoryCode) {
        return new LambdaQueryWrapper<MdmFactoryPlanningPolicyEntity>()
                .eq(MdmFactoryPlanningPolicyEntity::getTenantId, tenantId)
                .eq(MdmFactoryPlanningPolicyEntity::getFactoryCode, factoryCode)
                .eq(MdmFactoryPlanningPolicyEntity::getDelFlag, "0");
    }

    private <T> T inScope(Long tenantId, String factoryCode, Supplier<T> work) {
        Long oldTenant = TenantContextHolder.getTenantId();
        boolean oldTenantSkip = TenantContextHolder.getTenantSkip();
        String oldFactory = FactoryContextHolder.getFactoryCode();
        boolean oldFactorySkip = Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
        try {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
            TenantContextHolder.setTenantId(tenantId);
            FactoryContextHolder.setFactoryCode(factoryCode);
            return work.get();
        } finally {
            restoreScope(oldTenant, oldTenantSkip, oldFactory, oldFactorySkip);
        }
    }

    private <T> T withSkippedScope(Supplier<T> work) {
        Long oldTenant = TenantContextHolder.getTenantId();
        boolean oldTenantSkip = TenantContextHolder.getTenantSkip();
        String oldFactory = FactoryContextHolder.getFactoryCode();
        boolean oldFactorySkip = Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip());
        try {
            TenantContextHolder.clear();
            FactoryContextHolder.clear();
            TenantContextHolder.setTenantSkip();
            FactoryContextHolder.setFactorySkip();
            return work.get();
        } finally {
            restoreScope(oldTenant, oldTenantSkip, oldFactory, oldFactorySkip);
        }
    }

    private void restoreScope(Long tenant, boolean tenantSkip, String factory, boolean factorySkip) {
        TenantContextHolder.clear();
        FactoryContextHolder.clear();
        TenantContextHolder.setTenantId(tenant);
        if (tenantSkip) TenantContextHolder.setTenantSkip();
        FactoryContextHolder.setFactoryCode(factory);
        if (factorySkip) FactoryContextHolder.setFactorySkip();
    }

    private String fingerprint(MdmFactoryPlanningPolicyEntity row) {
        return sha256(String.join("|", "PLANNING_POLICY_V1", value(row.getTenantId()),
                value(row.getFactoryCode()), value(row.getVersionNo()), value(row.getMrpAuthority()),
                value(row.getErpSystemCode()), value(row.getExternalFactoryCode()),
                value(row.getProductionOrderSyncMode()), value(row.getSubstituteGovernanceMode()),
                value(row.getEffectiveFrom())));
    }
}
