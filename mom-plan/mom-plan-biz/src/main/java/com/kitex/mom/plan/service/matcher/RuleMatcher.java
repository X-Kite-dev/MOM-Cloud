package com.kitex.mom.plan.service.matcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.plan.entity.PlnIssueEntity;
import com.kitex.mom.plan.entity.PlnSchedulingRuleSetEntity;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RuleMatcher {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public RuleMatchResult match(PlnIssueEntity issue, PlnSchedulingRuleSetEntity ruleSet) {
        if (issue == null) {
            return new RuleMatchResult(false, List.of("ISSUE_NULL"), 0D);
        }
        if (ruleSet == null || ruleSet.getRuleContent() == null || ruleSet.getRuleContent().isBlank()) {
            return new RuleMatchResult(true, List.of(), 0D);
        }

        List<String> hardFailReasons = new ArrayList<>();
        List<String> matchedRules = new ArrayList<>();
        Map<String, Object> scoreBreakdown = new LinkedHashMap<>();
        Map<String, String> scoreDetail = new LinkedHashMap<>();
        double softScore = 0D;

        JsonNode root = parse(ruleSet.getRuleContent());
        if (root == null) {
            return new RuleMatchResult(false, List.of("RULE_CONTENT_INVALID_JSON"), 0D);
        }

        // ── 硬规则 ────────────────────────────────────────────────────────────
        JsonNode hardRules = root.get("hardRules");
        if (hardRules != null && hardRules.isArray()) {
            for (JsonNode rule : hardRules) {
                String type = text(rule, "type");
                if ("DEMAND_DATE_REQUIRED".equals(type)) {
                    if (issue.getDemandTime() == null) {
                        hardFailReasons.add("DEMAND_DATE_REQUIRED");
                    } else {
                        matchedRules.add("DEMAND_DATE_REQUIRED");
                    }
                } else if ("SCHEDULE_AFTER_DEMAND_FORBIDDEN".equals(type)) {
                    LocalDate scheduledDate = issue.getScheduledDate();
                    LocalDate demandTime = issue.getDemandTime();
                    if (scheduledDate != null && demandTime != null && scheduledDate.isAfter(demandTime)) {
                        hardFailReasons.add("SCHEDULE_AFTER_DEMAND_FORBIDDEN");
                    } else {
                        matchedRules.add("SCHEDULE_AFTER_DEMAND_FORBIDDEN");
                    }
                }
            }
        }

        // ── 软规则 ────────────────────────────────────────────────────────────
        JsonNode softRules = root.get("softRules");
        if (softRules != null && softRules.isArray()) {
            for (JsonNode rule : softRules) {
                String type = text(rule, "type");

                if ("EARLIER_DEMAND_FIRST".equals(type)) {
                    int weight = intValue(rule, "weight", 1);
                    LocalDate demandTime = issue.getDemandTime();
                    if (demandTime != null) {
                        long slackDays = ChronoUnit.DAYS.between(LocalDate.now(), demandTime);
                        // 分档：超期=100，≤3天=80，≤7天=60，≤14天=40，>14天=20
                        int tier;
                        String tierLabel;
                        if (slackDays <= 0) {
                            tier = 100; tierLabel = "超期";
                        } else if (slackDays <= 3) {
                            tier = 80; tierLabel = "紧急（≤3天）";
                        } else if (slackDays <= 7) {
                            tier = 60; tierLabel = "较紧（≤7天）";
                        } else if (slackDays <= 14) {
                            tier = 40; tierLabel = "一般（≤14天）";
                        } else {
                            tier = 20; tierLabel = "宽裕（>14天）";
                        }
                        double score = tier * (double) weight;
                        softScore += score;
                        matchedRules.add("EARLIER_DEMAND_FIRST");
                        scoreBreakdown.put("EARLIER_DEMAND_FIRST", score);
                        scoreDetail.put("EARLIER_DEMAND_FIRST",
                                "需求日期" + demandTime + "，距今" + slackDays + "天，命中「" + tierLabel + "」档（" + tier + "分）×权重" + weight + "，得分=" + String.format("%.2f", score));
                    }

                } else if ("SLACK_TIME".equals(type)) {
                    int weight = intValue(rule, "weight", 1);
                    int urgencyDays = intValue(rule, "urgencyDays", 7);
                    LocalDate demandTime = issue.getDemandTime();
                    if (demandTime != null) {
                        long slackDays = ChronoUnit.DAYS.between(LocalDate.now(), demandTime);
                        double urgencyBonus = slackDays <= 0 ? urgencyDays * 2.0 : Math.max(0, urgencyDays - slackDays);
                        double score = urgencyBonus * weight;
                        softScore += score;
                        matchedRules.add("SLACK_TIME");
                        scoreBreakdown.put("SLACK_TIME", score);
                        String slackDesc = slackDays <= 0
                                ? "已超期" + Math.abs(slackDays) + "天，紧急加分=" + String.format("%.1f", urgencyBonus)
                                : (slackDays < urgencyDays
                                        ? "距截止" + slackDays + "天（紧急阈值" + urgencyDays + "天），加分=" + String.format("%.1f", urgencyBonus)
                                        : "距截止" + slackDays + "天（宽裕），加分=0");
                        scoreDetail.put("SLACK_TIME", slackDesc + "，权重×" + weight + "，得分=" + String.format("%.2f", score));
                    }

                } else if ("LARGE_ORDER_FIRST".equals(type)) {
                    int weight = intValue(rule, "weight", 1);
                    if (issue.getNum() != null) {
                        double score = issue.getNum().doubleValue() * weight;
                        softScore += score;
                        matchedRules.add("LARGE_ORDER_FIRST");
                        scoreBreakdown.put("LARGE_ORDER_FIRST", score);
                        scoreDetail.put("LARGE_ORDER_FIRST",
                                "数量" + issue.getNum().toPlainString() + "×" + weight + "=" + String.format("%.2f", score));
                    }
                }
            }
        }

        if (!scoreDetail.isEmpty()) {
            scoreBreakdown.put("_detail", scoreDetail);
        }

        return new RuleMatchResult(hardFailReasons.isEmpty(), hardFailReasons, softScore, matchedRules, scoreBreakdown);
    }

    private JsonNode parse(String json) {
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null ? null : value.asText();
    }

    private int intValue(JsonNode node, String field, int defaultValue) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null ? defaultValue : value.asInt(defaultValue);
    }
}
