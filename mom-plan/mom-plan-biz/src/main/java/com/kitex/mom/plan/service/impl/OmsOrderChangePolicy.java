package com.kitex.mom.plan.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.oms.entity.OmsConfigEntity;
import com.kitex.mom.plan.service.OmsConfigService;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * OMS变更冻结区按当前工厂配置优先、租户通用配置兜底；配置歧义时故障关闭。
 */
final class OmsOrderChangePolicy {
    private OmsOrderChangePolicy() {
    }

    static LocalDate frozenThrough(OmsConfigService service, String factoryCode) {
        if (StrUtil.isBlank(factoryCode)) throw new CheckedException("订单变更缺少工厂，无法读取冻结区策略");
        List<OmsConfigEntity> policies = service.list(new LambdaQueryWrapper<OmsConfigEntity>()
                .and(q -> q.eq(OmsConfigEntity::getFactoryCode, factoryCode)
                        .or().isNull(OmsConfigEntity::getFactoryCode)
                        .or().eq(OmsConfigEntity::getFactoryCode, "")));
        List<OmsConfigEntity> specific = policies.stream()
                .filter(row -> Objects.equals(factoryCode, row.getFactoryCode())).toList();
        List<OmsConfigEntity> defaults = policies.stream()
                .filter(row -> StrUtil.isBlank(row.getFactoryCode())).toList();
        if (specific.size() > 1 || specific.isEmpty() && defaults.size() > 1) {
            throw new CheckedException("订单变更冻结区策略存在多份同优先级配置，请先治理");
        }
        OmsConfigEntity selected = specific.isEmpty() ? defaults.stream().findFirst().orElse(null) : specific.get(0);
        if (selected == null || selected.getFrozenDay() == null || selected.getFrozenDay() == 0) return null;
        if (selected.getFrozenDay() < 0 || selected.getFrozenDay() > 365) {
            throw new CheckedException("订单冻结天数配置必须在0至365天之间");
        }
        return LocalDate.now().plusDays(selected.getFrozenDay());
    }
}
