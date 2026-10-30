package com.kitex.mom.wms.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.entity.WmsReservationMovementPolicyEntity;
import com.kitex.mom.wms.mapper.WmsReservationMovementPolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 按租户和工厂控制既有生产、销售预留入口，不改变安全库存和已有预留的消费。
 */
@Service
@RequiredArgsConstructor
public class WmsReservationMovementPolicyService {
    private static final Set<String> SUPPORTED_TYPES = Set.of("produce", "sales");

    private final WmsReservationMovementPolicyMapper mapper;

    public void ensureProductionReservable(String sourceType) {
        if (!"PLAN_MRP_MAIN".equals(sourceType) && !"PLAN_MRP_SUBSTITUTE".equals(sourceType)) {
            throw new CheckedException("不支持的物料预留来源类型");
        }
        ensureReservable("produce");
    }

    public void ensureSalesReservable() {
        ensureReservable("sales");
    }

    /** 与正式新建预留共用规则解析，不改变库存或已有预留。 */
    public Evaluation preview(String movementType) {
        String type = StrUtil.trim(movementType);
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new CheckedException("不支持的预留作业类型");
        }
        Scope scope = scope();
        List<WmsReservationMovementPolicyEntity> policies = mapper.selectList(
                new LambdaQueryWrapper<WmsReservationMovementPolicyEntity>()
                        .eq(WmsReservationMovementPolicyEntity::getTenantId, scope.tenant())
                        .eq(WmsReservationMovementPolicyEntity::getFactoryCode, scope.factory())
                        .eq(WmsReservationMovementPolicyEntity::getMovementType, type)
                        .eq(WmsReservationMovementPolicyEntity::getStatus, true));
        if (policies == null) {
            throw new CheckedException("读取作业类型预留策略失败，禁止继续预留");
        }
        List<WmsReservationMovementPolicyEntity> active = policies.stream()
                .filter(item -> Boolean.TRUE.equals(item.getStatus())
                        && type.equals(item.getMovementType())
                        && Objects.equals(scope.tenant(), item.getTenantId())
                        && Objects.equals(scope.factory(), item.getFactoryCode()))
                .toList();
        if (active.size() > 1) {
            throw new CheckedException("作业类型存在多条启用的预留策略");
        }
        if (active.isEmpty()) {
            return new Evaluation(true, "DEFAULT_BEHAVIOR", null,
                    "当前工厂未启用该作业类型策略，沿用原有预留行为");
        }
        WmsReservationMovementPolicyEntity policy = active.get(0);
        boolean allowed = Boolean.TRUE.equals(policy.getReservationAllowed());
        return new Evaluation(allowed, "FACTORY_POLICY", policy.getId(),
                allowed ? "当前工厂策略允许新建库存预留" : "当前工厂策略禁止新建库存预留");
    }

    public record Evaluation(boolean allowed, String configurationSource, Long policyId, String reason) {
    }

    public List<WmsReservationMovementPolicyEntity> listPolicies() {
        Scope scope = scope();
        return mapper.selectList(new LambdaQueryWrapper<WmsReservationMovementPolicyEntity>()
                .eq(WmsReservationMovementPolicyEntity::getTenantId, scope.tenant())
                .eq(WmsReservationMovementPolicyEntity::getFactoryCode, scope.factory())
                .orderByAsc(WmsReservationMovementPolicyEntity::getMovementType));
    }

    @Transactional(rollbackFor = Exception.class)
    public WmsReservationMovementPolicyEntity savePolicy(WmsReservationMovementPolicyEntity policy) {
        if (policy == null || StrUtil.isBlank(policy.getMovementType())
                || !SUPPORTED_TYPES.contains(StrUtil.trim(policy.getMovementType()))
                || policy.getReservationAllowed() == null || policy.getStatus() == null
                || StrUtil.length(policy.getRemark()) > 500) {
            throw new CheckedException("作业类型预留策略无效");
        }
        Scope scope = scope();
        String movementType = policy.getMovementType().trim();
        if (policy.getId() != null) {
            WmsReservationMovementPolicyEntity existing = mapper.selectById(policy.getId());
            if (existing == null || !Objects.equals(existing.getTenantId(), scope.tenant())
                    || !Objects.equals(existing.getFactoryCode(), scope.factory())) {
                throw new CheckedException("作业类型预留策略不存在或不属于当前工厂");
            }
            if (!Objects.equals(existing.getMovementType(), movementType)) {
                throw new CheckedException("已建立的预留策略不能更换作业类型");
            }
        }
        Long duplicates = mapper.selectCount(new LambdaQueryWrapper<WmsReservationMovementPolicyEntity>()
                .eq(WmsReservationMovementPolicyEntity::getTenantId, scope.tenant())
                .eq(WmsReservationMovementPolicyEntity::getFactoryCode, scope.factory())
                .eq(WmsReservationMovementPolicyEntity::getMovementType, movementType)
                .eq(WmsReservationMovementPolicyEntity::getStatus, true)
                .ne(policy.getId() != null, WmsReservationMovementPolicyEntity::getId, policy.getId()));
        if (Boolean.TRUE.equals(policy.getStatus()) && duplicates > 0) {
            throw new CheckedException("当前工厂该作业类型已有启用策略");
        }
        policy.setMovementType(movementType);
        policy.setFactoryCode(scope.factory());
        policy.setTenantId(scope.tenant());
        policy.setDelFlag("0");
        int changed;
        if (policy.getId() == null) {
            changed = mapper.insert(policy);
        } else {
            changed = mapper.update(null, new LambdaUpdateWrapper<WmsReservationMovementPolicyEntity>()
                    .eq(WmsReservationMovementPolicyEntity::getId, policy.getId())
                    .eq(WmsReservationMovementPolicyEntity::getTenantId, scope.tenant())
                    .eq(WmsReservationMovementPolicyEntity::getFactoryCode, scope.factory())
                    .set(WmsReservationMovementPolicyEntity::getMovementType, movementType)
                    .set(WmsReservationMovementPolicyEntity::getReservationAllowed,
                            policy.getReservationAllowed())
                    .set(WmsReservationMovementPolicyEntity::getStatus, policy.getStatus())
                    .set(WmsReservationMovementPolicyEntity::getRemark, StrUtil.emptyToNull(
                            StrUtil.trim(policy.getRemark()))));
        }
        if (changed != 1) {
            throw new CheckedException("保存作业类型预留策略失败");
        }
        return policy;
    }

    private void ensureReservable(String movementType) {
        if (!preview(movementType).allowed()) {
            throw new CheckedException("当前工厂的[" + movementType + "]作业类型不允许新建库存预留");
        }
    }

    private Scope scope() {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        if (tenant == null || StrUtil.isBlank(factory)
                || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("作业类型预留策略必须具有租户和工厂上下文");
        }
        return new Scope(tenant, factory);
    }

    private record Scope(Long tenant, String factory) {
    }
}
