package com.kitex.mom.plan.service.matcher;

import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnSchedulingRuleSetEntity;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleMatcherTest {

    private final RuleMatcher ruleMatcher = new RuleMatcher();

    @Test
    void should_fail_when_demand_date_required_but_missing() {
        PlnIssueEntity issue = new PlnIssueEntity();
        issue.setScheduledDate(LocalDate.of(2026, 4, 10));
        PlnSchedulingRuleSetEntity ruleSet = new PlnSchedulingRuleSetEntity();
        ruleSet.setRuleContent("{\"hardRules\":[{\"type\":\"DEMAND_DATE_REQUIRED\"}]}");
        RuleMatchResult result = ruleMatcher.match(issue, ruleSet);
        assertFalse(result.isPassed());
        assertTrue(result.getHardFailReasons().contains("DEMAND_DATE_REQUIRED"));
    }

    @Test
    void should_pass_when_hard_rules_are_satisfied() {
        PlnIssueEntity issue = new PlnIssueEntity();
        issue.setDemandTime(LocalDate.of(2026, 4, 12));
        issue.setScheduledDate(LocalDate.of(2026, 4, 11));
        PlnSchedulingRuleSetEntity ruleSet = new PlnSchedulingRuleSetEntity();
        ruleSet.setRuleContent("{\"hardRules\":[{\"type\":\"DEMAND_DATE_REQUIRED\"},{\"type\":\"SCHEDULE_AFTER_DEMAND_FORBIDDEN\"}]}");
        RuleMatchResult result = ruleMatcher.match(issue, ruleSet);
        assertTrue(result.isPassed());
        assertTrue(result.getHardFailReasons().isEmpty());
    }

    @Test
    void should_fail_close_when_rule_content_is_invalid_json() {
        PlnIssueEntity issue = new PlnIssueEntity();
        issue.setDemandTime(LocalDate.of(2026, 4, 12));
        issue.setScheduledDate(LocalDate.of(2026, 4, 11));
        PlnSchedulingRuleSetEntity ruleSet = new PlnSchedulingRuleSetEntity();
        ruleSet.setRuleContent("{invalid-json");
        RuleMatchResult result = ruleMatcher.match(issue, ruleSet);
        assertFalse(result.isPassed());
        assertTrue(result.getHardFailReasons().contains("RULE_CONTENT_INVALID_JSON"));
    }

    @Test
    void should_score_earlier_demand_higher_than_later_demand() {
        PlnSchedulingRuleSetEntity ruleSet = new PlnSchedulingRuleSetEntity();
        ruleSet.setRuleContent("{\"softRules\":[{\"type\":\"EARLIER_DEMAND_FIRST\",\"weight\":10}]}");
        PlnIssueEntity early = new PlnIssueEntity();
        LocalDate observed = LocalDate.now();
        early.setDemandTime(observed.plusDays(2));
        PlnIssueEntity later = new PlnIssueEntity();
        later.setDemandTime(observed.plusDays(10));
        RuleMatchResult earlyResult = ruleMatcher.match(early, ruleSet);
        RuleMatchResult laterResult = ruleMatcher.match(later, ruleSet);
        assertTrue(earlyResult.isPassed());
        assertTrue(laterResult.isPassed());
        org.junit.jupiter.api.Assertions.assertEquals(800D, earlyResult.getSoftScore());
        org.junit.jupiter.api.Assertions.assertEquals(400D, laterResult.getSoftScore());
        assertTrue(earlyResult.getSoftScore() > laterResult.getSoftScore());
    }
}
