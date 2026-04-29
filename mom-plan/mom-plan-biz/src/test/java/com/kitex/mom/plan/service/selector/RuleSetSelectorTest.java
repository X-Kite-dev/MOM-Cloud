package com.kitex.mom.plan.service.selector;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kitex.mom.plan.entity.PlnSchedulingRuleSetEntity;
import com.kitex.mom.plan.service.PlnSchedulingRuleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleSetSelectorTest {

    @Mock
    private PlnSchedulingRuleService plnSchedulingRuleService;

    @InjectMocks
    private RuleSetSelector ruleSetSelector;

    @Test
    void should_prefer_default_rule_when_multiple_rules_match() {
        PlnSchedulingRuleSetEntity nonDefault = rule("R-NON-DEFAULT", false, 99, "F1", LocalDateTime.of(2026, 4, 1, 10, 0));
        PlnSchedulingRuleSetEntity defaultRule = rule("R-DEFAULT", true, 1, "F1", LocalDateTime.of(2026, 4, 1, 9, 0));

        when(plnSchedulingRuleService.list(any(Wrapper.class))).thenReturn(List.of(nonDefault, defaultRule));

        PlnSchedulingRuleSetEntity selected = ruleSetSelector.selectActiveRule("F1", LocalDateTime.now());

        assertEquals("R-DEFAULT", selected.getRuleCode());
    }

    @Test
    void should_prefer_higher_version_when_default_flag_is_same() {
        PlnSchedulingRuleSetEntity v1 = rule("R-V1", true, 1, "F1", LocalDateTime.of(2026, 4, 1, 10, 0));
        PlnSchedulingRuleSetEntity v2 = rule("R-V2", true, 2, "F1", LocalDateTime.of(2026, 4, 1, 9, 0));

        when(plnSchedulingRuleService.list(any(Wrapper.class))).thenReturn(List.of(v1, v2));

        PlnSchedulingRuleSetEntity selected = ruleSetSelector.selectActiveRule("F1", LocalDateTime.now());

        assertEquals("R-V2", selected.getRuleCode());
    }

    @Test
    void should_prefer_factory_specific_rule_over_global_rule() {
        PlnSchedulingRuleSetEntity factoryRule = rule("R-F1", false, 1, "F1", LocalDateTime.of(2026, 4, 1, 9, 0));
        PlnSchedulingRuleSetEntity globalRule = rule("R-GLOBAL", true, 99, null, LocalDateTime.of(2026, 4, 1, 10, 0));

        when(plnSchedulingRuleService.list(any(Wrapper.class))).thenReturn(List.of(factoryRule, globalRule));

        PlnSchedulingRuleSetEntity selected = ruleSetSelector.selectActiveRule("F1", LocalDateTime.now());

        assertEquals("R-F1", selected.getRuleCode());
    }

    @Test
    void should_return_null_when_no_rule_matches_scope() {
        PlnSchedulingRuleSetEntity scopedRule = rule("R-F2", true, 1, "F2", LocalDateTime.now());

        when(plnSchedulingRuleService.list(any(Wrapper.class))).thenReturn(List.of(scopedRule));

        PlnSchedulingRuleSetEntity selected = ruleSetSelector.selectActiveRule("F1", LocalDateTime.now());

        assertNull(selected);
    }

    @Test
    void should_not_select_inactive_rule_even_if_higher_priority() {
        PlnSchedulingRuleSetEntity inactiveHighPriority = rule("R-INACTIVE", true, 999, "F1", LocalDateTime.of(2026, 4, 2, 10, 0));
        inactiveHighPriority.setActive(false);
        PlnSchedulingRuleSetEntity activeLowerPriority = rule("R-ACTIVE", false, 1, "F1", LocalDateTime.of(2026, 4, 1, 10, 0));

        when(plnSchedulingRuleService.list(any(Wrapper.class))).thenReturn(List.of(inactiveHighPriority, activeLowerPriority));

        PlnSchedulingRuleSetEntity selected = ruleSetSelector.selectActiveRule("F1", LocalDateTime.now());

        assertEquals("R-ACTIVE", selected.getRuleCode());
    }

    @Test
    void should_select_global_rule_when_no_factory_exact_match() {
        PlnSchedulingRuleSetEntity globalRule = rule("R-GLOBAL", true, 2, null, LocalDateTime.of(2026, 4, 3, 8, 0));
        PlnSchedulingRuleSetEntity otherFactoryRule = rule("R-F2", true, 99, "F2", LocalDateTime.of(2026, 4, 3, 9, 0));

        when(plnSchedulingRuleService.list(any(Wrapper.class))).thenReturn(List.of(otherFactoryRule, globalRule));

        PlnSchedulingRuleSetEntity selected = ruleSetSelector.selectActiveRule("F1", LocalDateTime.now());

        assertEquals("R-GLOBAL", selected.getRuleCode());
    }

    @Test
    void should_prefer_newer_update_time_when_default_and_version_are_equal() {
        PlnSchedulingRuleSetEntity older = rule("R-OLD", true, 3, "F1", LocalDateTime.of(2026, 4, 1, 8, 0));
        PlnSchedulingRuleSetEntity newer = rule("R-NEW", true, 3, "F1", LocalDateTime.of(2026, 4, 1, 9, 0));

        when(plnSchedulingRuleService.list(any(Wrapper.class))).thenReturn(List.of(older, newer));

        PlnSchedulingRuleSetEntity selected = ruleSetSelector.selectActiveRule("F1", LocalDateTime.now());

        assertEquals("R-NEW", selected.getRuleCode());
    }

    @Test
    void should_always_match_effective_window_extension_point_currently() {
        PlnSchedulingRuleSetEntity rule = rule("R-WINDOW", true, 1, "F1", LocalDateTime.of(2020, 1, 1, 0, 0));

        when(plnSchedulingRuleService.list(any(Wrapper.class))).thenReturn(List.of(rule));

        PlnSchedulingRuleSetEntity selected = ruleSetSelector.selectActiveRule("F1", LocalDateTime.of(2099, 1, 1, 0, 0));

        assertEquals("R-WINDOW", selected.getRuleCode());
    }

    private PlnSchedulingRuleSetEntity rule(String code,
                                            boolean defaultRule,
                                            Integer version,
                                            String factoryCode,
                                            LocalDateTime updateTime) {
        PlnSchedulingRuleSetEntity entity = new PlnSchedulingRuleSetEntity();
        entity.setRuleCode(code);
        entity.setActive(true);
        entity.setDefaultRule(defaultRule);
        entity.setRuleVersion(version);
        entity.setFactoryCode(factoryCode);
        entity.setUpdateTime(updateTime);
        return entity;
    }
}
