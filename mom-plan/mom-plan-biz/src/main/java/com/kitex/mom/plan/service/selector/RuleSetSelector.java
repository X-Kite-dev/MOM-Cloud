package com.kitex.mom.plan.service.selector;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.mom.plan.entity.PlnSchedulingRuleSetEntity;
import com.kitex.mom.plan.service.PlnSchedulingRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 排产规则选择器
 *
 * <p>当前最小实现：</p>
 * <ul>
 *   <li>仅选择 active=true 的规则</li>
 *   <li>作用域优先 factoryCode 精确匹配；若规则未配置 factoryCode 视为全局规则</li>
 *   <li>有效期校验预留扩展口（当前实体无有效期字段，默认视为命中）</li>
 *   <li>多条命中时按 defaultRule desc, ruleVersion desc, updateTime desc 排序</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RuleSetSelector {

    private final PlnSchedulingRuleService plnSchedulingRuleService;

    public PlnSchedulingRuleSetEntity selectActiveRule() {
        return selectActiveRule(FactoryContextHolder.getFactoryCode(), LocalDateTime.now());
    }

    PlnSchedulingRuleSetEntity selectActiveRule(String factoryCode, LocalDateTime now) {
        List<PlnSchedulingRuleSetEntity> rules = plnSchedulingRuleService.list(
                Wrappers.<PlnSchedulingRuleSetEntity>lambdaQuery()
                        .eq(PlnSchedulingRuleSetEntity::getActive, true)
        );

        if (rules == null || rules.isEmpty()) {
            return null;
        }

        List<PlnSchedulingRuleSetEntity> scopedRules = rules.stream()
                .filter(Objects::nonNull)
                .filter(rule -> Boolean.TRUE.equals(rule.getActive()))
                .filter(rule -> matchesEffectiveWindow(rule, now))
                .toList();

        List<PlnSchedulingRuleSetEntity> factoryRules = scopedRules.stream()
                .filter(rule -> isFactoryExactMatch(rule, factoryCode))
                .sorted(rulePriorityComparator())
                .toList();

        if (!factoryRules.isEmpty()) {
            return factoryRules.get(0);
        }

        return scopedRules.stream()
                .filter(this::isGlobalScope)
                .sorted(rulePriorityComparator())
                .findFirst()
                .orElse(null);
    }

    private boolean isFactoryExactMatch(PlnSchedulingRuleSetEntity rule, String factoryCode) {
        if (factoryCode == null || factoryCode.isBlank()) {
            return false;
        }
        return factoryCode.equals(rule.getFactoryCode());
    }

    private boolean isGlobalScope(PlnSchedulingRuleSetEntity rule) {
        String ruleFactoryCode = rule.getFactoryCode();
        return ruleFactoryCode == null || ruleFactoryCode.isBlank();
    }

    private boolean matchesEffectiveWindow(PlnSchedulingRuleSetEntity rule, LocalDateTime now) {
        // 扩展点：当前 PlnSchedulingRuleSetEntity 尚无生效起止字段，暂按“命中有效期”处理。
        // 后续若新增 startTime/endTime（或同义字段），可在此集中校验。
        return true;
    }

    private Comparator<PlnSchedulingRuleSetEntity> rulePriorityComparator() {
        return Comparator
                .comparing((PlnSchedulingRuleSetEntity item) -> Boolean.TRUE.equals(item.getDefaultRule()))
                .reversed()
                .thenComparing(item -> item.getRuleVersion() == null ? Integer.MIN_VALUE : item.getRuleVersion(), Comparator.reverseOrder())
                .thenComparing(PlnSchedulingRuleSetEntity::getUpdateTime, Comparator.nullsLast(Comparator.reverseOrder()));
    }

}
