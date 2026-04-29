package com.kitex.mom.plan.service.matcher;

import java.util.*;

public class RuleMatchResult {

    private final boolean passed;
    private final List<String> hardFailReasons;
    private final double softScore;
    private final List<String> matchedRules;
    private final Map<String, Object> scoreBreakdown;

    public RuleMatchResult(boolean passed, List<String> hardFailReasons, double softScore) {
        this(passed, hardFailReasons, softScore, List.of(), Map.of());
    }

    public RuleMatchResult(boolean passed,
                           List<String> hardFailReasons,
                           double softScore,
                           List<String> matchedRules,
                           Map<String, Object> scoreBreakdown) {
        this.passed = passed;
        this.hardFailReasons = hardFailReasons == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(hardFailReasons));
        this.softScore = softScore;
        this.matchedRules = matchedRules == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(matchedRules));
        this.scoreBreakdown = scoreBreakdown == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(scoreBreakdown));
    }

    public boolean isPassed() {
        return passed;
    }

    public List<String> getHardFailReasons() {
        return hardFailReasons;
    }

    public double getSoftScore() {
        return softScore;
    }

    public List<String> getMatchedRules() {
        return matchedRules;
    }

    public Map<String, Object> getScoreBreakdown() {
        return scoreBreakdown;
    }
}
