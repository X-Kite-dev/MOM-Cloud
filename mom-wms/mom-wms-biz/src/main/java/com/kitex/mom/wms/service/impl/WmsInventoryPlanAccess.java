package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanDetailEntity;
import com.kitex.mom.wms.entity.WmsInsideInventoryPlanEntity;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanDetailMapper;
import com.kitex.mom.wms.mapper.WmsInsideInventoryPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Shared lock order: plan, then details by id. Call write methods inside a transaction.
 */
@Component
@RequiredArgsConstructor
public class WmsInventoryPlanAccess {
    private final WmsInsideInventoryPlanMapper plans;
    private final WmsInsideInventoryPlanDetailMapper details;

    public static String factory() {
        require(TenantContextHolder.getTenantId() != null
                        && StrUtil.isNotBlank(FactoryContextHolder.getFactoryCode())
                        && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                        && !Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip()),
                "盘点必须具有明确的租户和工厂上下文");
        return FactoryContextHolder.getFactoryCode();
    }

    public static BigDecimal number(Double value, String label) {
        require(value != null && Double.isFinite(value) && value >= 0, label + "必须为已录入的非负有效数量");
        return BigDecimal.valueOf(value);
    }

    public static void require(boolean valid, String message) {
        if (!valid) {
            throw new CheckedException(message);
        }
    }

    public WmsInsideInventoryPlanEntity plan(Long id, boolean lock) {
        require(id != null, "盘点计划不能为空");
        var row = plans.selectOne(Wrappers.<WmsInsideInventoryPlanEntity>lambdaQuery()
                .eq(WmsInsideInventoryPlanEntity::getId, id)
                .eq(WmsInsideInventoryPlanEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(WmsInsideInventoryPlanEntity::getFactoryCode, factory()).last(lock ? "FOR UPDATE" : ""));
        require(row != null && Objects.equals(row.getTenantId(), TenantContextHolder.getTenantId())
                && Objects.equals(row.getFactoryCode(), factory()), "盘点计划不存在或不属于当前范围");
        return row;
    }

    public WmsInsideInventoryPlanDetailEntity detail(Long id) {
        require(id != null, "盘点明细不能为空");
        var query = Wrappers.<WmsInsideInventoryPlanDetailEntity>lambdaQuery()
                .eq(WmsInsideInventoryPlanDetailEntity::getId, id)
                .eq(WmsInsideInventoryPlanDetailEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(WmsInsideInventoryPlanDetailEntity::getFactoryCode, factory());
        var observed = details.selectOne(query);
        require(observed != null, "盘点明细不存在或不属于当前范围");
        plan(observed.getPlanId(), true);
        var locked = details.selectOne(query.last("FOR UPDATE"));
        require(locked != null && Objects.equals(observed.getPlanId(), locked.getPlanId())
                && Objects.equals(locked.getTenantId(), TenantContextHolder.getTenantId())
                && Objects.equals(locked.getFactoryCode(), factory()), "盘点明细范围已变化，请刷新核对");
        return locked;
    }

    public List<WmsInsideInventoryPlanDetailEntity> details(Long planId, boolean lock) {
        var result = details.selectList(Wrappers.<WmsInsideInventoryPlanDetailEntity>lambdaQuery()
                .eq(WmsInsideInventoryPlanDetailEntity::getPlanId, planId)
                .eq(WmsInsideInventoryPlanDetailEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(WmsInsideInventoryPlanDetailEntity::getFactoryCode, factory())
                .orderByAsc(WmsInsideInventoryPlanDetailEntity::getId).last(lock ? "FOR UPDATE" : ""));
        require(result != null && !result.isEmpty(), "盘点计划没有可办理明细");
        return result;
    }
}
