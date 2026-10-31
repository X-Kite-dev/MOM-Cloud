package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.dto.MesProductionTimeDTO.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * 真实来源区间的统计计算，组织范围由查询服务校验。
 */
public class MesProductionTimeCalculator {

    private static boolean unclassified(Fact fact) {
        return blank(fact.getReason()) || Set.of("MANUAL_PAUSE", "MOBILE_MANUAL_PAUSE", "PAUSE", "UNKNOWN").contains(fact.getReason());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String value(String value) {
        return blank(value) ? "UNKNOWN" : value;
    }

    private static long micros(LocalDateTime start, LocalDateTime end) {
        Duration duration = Duration.between(start, end);
        return Math.addExact(Math.multiplyExact(duration.getSeconds(), 1_000_000), duration.getNano() / 1000);
    }

    private static String seconds(long micros) {
        return BigDecimal.valueOf(micros, 6).stripTrailingZeros().toPlainString();
    }

    public Result calculate(Query query, List<Fact> facts, LocalDateTime observedAt) {
        if (query == null || query.getStartTime() == null || query.getEndTime() == null
                || !query.getEndTime().isAfter(query.getStartTime()) || observedAt == null) {
            throw new CheckedException("统计必须有明确的开始、结束和观察时刻");
        }
        String dimension = Objects.requireNonNullElse(query.getGroupBy(), "TASK");
        if (!Set.of("TASK", "PROCESS", "ARRANGE", "WORKPIECE").contains(dimension)) {
            throw new CheckedException("不支持的生产时间分组");
        }
        List<Detail> details = new ArrayList<>();
        Set<String> gaps = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        for (Fact fact : facts) {
            if (fact == null || fact.getSourceType() == null || fact.getSourceId() == null
                    || !seen.add(fact.getSourceType() + ":" + fact.getSourceId())) {
                throw new CheckedException("生产时间来源缺失或重复，不能生成统计");
            }
            Detail detail = clip(query, fact, observedAt);
            if (detail == null) {
                continue;
            }
            details.add(detail);
            if (detail.getIssue() != null) {
                gaps.add(detail.getIssue());
            }
        }
        details.sort(Comparator.comparing((Detail d) -> d.getFact().getStartTime(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(d -> d.getFact().getSourceType()).thenComparing(d -> d.getFact().getSourceId()));
        Map<List<String>, List<Detail>> grouped = new TreeMap<>(Comparator.comparing(Object::toString));
        for (Detail detail : details) {
            Fact fact = detail.getFact();
            if ("WORKPIECE".equals(dimension) && (blank(fact.getIdentifier())
                    || !Set.of("WORKPIECE_RUN", "WORKPIECE_PAUSE").contains(value(fact.getKind())))) {
                continue;
            }
            List<String> key = switch (dimension) {
                case "PROCESS" -> List.of(value(fact.getTaskCode()), value(fact.getProduceTaskCode()));
                case "ARRANGE" ->
                        List.of(value(fact.getTaskCode()), value(fact.getProduceTaskCode()), value(fact.getArrangeCode()));
                case "WORKPIECE" ->
                        List.of(value(fact.getTaskCode()), value(fact.getIdentifierType()), value(fact.getIdentifier()));
                default -> List.of(value(fact.getTaskCode()));
            };
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(detail);
        }
        List<Group> groups = new ArrayList<>();
        grouped.forEach((key, rows) -> {
            Fact first = rows.get(0).getFact();
            groups.add(new Group().setKey(key.toString()).setLabel(String.join(" / ", key))
                    .setTaskCode(first.getTaskCode())
                    .setProduceTaskCode(Set.of("PROCESS", "ARRANGE").contains(dimension) ? first.getProduceTaskCode() : null)
                    .setArrangeCode("ARRANGE".equals(dimension) ? first.getArrangeCode() : null)
                    .setIdentifier("WORKPIECE".equals(dimension) ? first.getIdentifier() : null)
                    .setIdentifierType("WORKPIECE".equals(dimension) ? first.getIdentifierType() : null)
                    .setMetrics(summarize(rows)));
        });
        if (details.stream().noneMatch(d -> "WORKPIECE_RUN".equals(d.getFact().getKind()) && d.isIncluded())) {
            gaps.add("没有有效单件/批次加工区间，不能将派工运行时间分摊为工件实测时间");
        }
        int page = query.getDetailPage() == null ? 1 : query.getDetailPage();
        int size = query.getDetailSize() == null ? 50 : query.getDetailSize();
        if (page < 1 || size < 1 || size > 200) {
            throw new CheckedException("明细页码须为正数，每页1至200条");
        }
        long from = ((long) page - 1) * size;
        List<Detail> visible = from >= details.size() ? List.of()
                : details.subList((int) from, (int) Math.min(details.size(), from + size));
        return new Result().setScope(query).setObservedAt(observedAt).setTotals(summarize(details))
                .setGroups(groups).setDetails(new ArrayList<>(visible)).setDetailTotal(details.size())
                .setDataGaps(new ArrayList<>(gaps)).setMetricDefinitions(List.of(
                        "统计区间开始含、结束不含；按真实区间与统计窗交集计秒，微秒先汇总再转换秒数。",
                        "加工累计按派工作业区间计，不是工单自然历时；并行作业可以累计，人时单独统计。",
                        "派工及工件的已闭合加工、进行中加工分别返回；未来结束、历史缺口及无效来源不计为正常加工。",
                        "暂停按同一派工区间并集累计，不代表设备已物理停机，也不推测受影响人员人时。",
                        "人员工时仅计已批准及待批准作废的有效申报；没有申报表示缺依据，不代表无人工作。",
                        "单件/批次工时仅使用实际采集区间，缺失时为空；总量由全部命中来源计算，不受明细分页影响。"));
    }

    private Detail clip(Query query, Fact fact, LocalDateTime observed) {
        LocalDateTime start = fact.getStartTime();
        LocalDateTime end = fact.getEndTime();
        String issue = null;
        boolean open = end == null && "OPEN".equals(fact.getState());
        if (start != null && !start.isBefore(query.getEndTime())) {
            return null;
        }
        if (start != null && end != null && end.isAfter(start) && !end.isAfter(query.getStartTime())) {
            return null;
        }
        if (!Boolean.TRUE.equals(fact.getEligible())) {
            issue = "来源不完整或无效：" + fact.getSourceType() + "/" + fact.getSourceId() + " " + value(fact.getReason());
        } else if (start == null || start.isAfter(observed) || end == null && !open
                || end != null && (end.isBefore(start) || end.isAfter(observed))) {
            issue = "来源起止时间不可核定：" + fact.getSourceType() + "/" + fact.getSourceId();
        } else if (!Set.of("RUN", "LABOR", "PAUSE", "WORKPIECE_RUN", "WORKPIECE_PAUSE").contains(value(fact.getKind()))) {
            issue = "来源时间类型未知：" + fact.getSourceType() + "/" + fact.getSourceId();
        } else if (blank(fact.getTaskCode()) || blank(fact.getArrangeCode())) {
            issue = "来源缺少唯一工单或派工归属：" + fact.getSourceType() + "/" + fact.getSourceId();
        } else if (fact.getKind().startsWith("WORKPIECE_") && (blank(fact.getIdentifier()) || blank(fact.getIdentifierType()))) {
            issue = "来源缺少单件/批次身份：" + fact.getSourceType() + "/" + fact.getSourceId();
        }
        if (issue != null) {
            return new Detail().setFact(fact).setIncluded(false).setIssue(issue);
        }
        LocalDateTime clippedStart = start.isAfter(query.getStartTime()) ? start : query.getStartTime();
        LocalDateTime effectiveEnd = open ? observed : end;
        LocalDateTime clippedEnd = effectiveEnd.isBefore(query.getEndTime()) ? effectiveEnd : query.getEndTime();
        boolean observedZero = clippedEnd.equals(clippedStart) && clippedStart.equals(start)
                && effectiveEnd.equals(start);
        if (clippedEnd.isBefore(clippedStart) || clippedEnd.equals(clippedStart) && !observedZero) {
            return null;
        }
        if (("PAUSE".equals(fact.getKind()) || "WORKPIECE_PAUSE".equals(fact.getKind())) && unclassified(fact)) {
            issue = "暂停原因尚未分类：" + fact.getArrangeCode();
        }
        return new Detail().setFact(fact).setWindowStart(clippedStart).setWindowEnd(clippedEnd)
                .setSeconds(seconds(micros(clippedStart, clippedEnd))).setIncluded(true).setOngoing(open).setIssue(issue);
    }

    private Metrics summarize(List<Detail> rows) {
        Metrics result = new Metrics();
        Map<String, List<Detail>> metricRows = new HashMap<>();
        for (Detail detail : rows) {
            if (!detail.isIncluded()) {
                result.setInvalidSourceCount(result.getInvalidSourceCount() + 1);
                continue;
            }
            Fact fact = detail.getFact();
            if (detail.isOngoing()) {
                result.setOpenIntervalCount(result.getOpenIntervalCount() + 1);
            }
            String metric = switch (fact.getKind()) {
                case "RUN" -> detail.isOngoing() ? "OPEN_RUN" : "RUN";
                case "WORKPIECE_RUN" -> detail.isOngoing() ? "WORKPIECE_OPEN_RUN" : "WORKPIECE_RUN";
                default -> fact.getKind();
            };
            metricRows.computeIfAbsent(metric, ignored -> new ArrayList<>()).add(detail);
            if (("PAUSE".equals(fact.getKind()) || "WORKPIECE_PAUSE".equals(fact.getKind())) && unclassified(fact)) {
                result.setUnclassifiedPauseCount(result.getUnclassifiedPauseCount() + 1);
            }
        }
        set(result, metricRows, "RUN", Metrics::setProcessingSeconds);
        set(result, metricRows, "OPEN_RUN", Metrics::setOpenProcessingSeconds);
        set(result, metricRows, "LABOR", Metrics::setLaborSeconds);
        set(result, metricRows, "PAUSE", Metrics::setPausedSeconds);
        set(result, metricRows, "WORKPIECE_RUN", Metrics::setWorkpieceProcessingSeconds);
        set(result, metricRows, "WORKPIECE_OPEN_RUN", Metrics::setOpenWorkpieceProcessingSeconds);
        set(result, metricRows, "WORKPIECE_PAUSE", Metrics::setWorkpiecePausedSeconds);
        return result;
    }

    private void set(Metrics metrics, Map<String, List<Detail>> rows, String kind, BiConsumer<Metrics, String> setter) {
        List<Detail> values = rows.get(kind);
        if (values == null) {
            return;
        }
        Map<List<String>, List<Detail>> resources = new HashMap<>();
        for (Detail detail : values) {
            Fact fact = detail.getFact();
            List<String> resource = "LABOR".equals(kind) ? List.of(value(fact.getWorkerCode()))
                    : kind.startsWith("WORKPIECE") ? List.of(value(fact.getArrangeCode()), value(fact.getIdentifierType()), value(fact.getIdentifier()))
                    : List.of(value(fact.getArrangeCode()));
            resources.computeIfAbsent(resource, ignored -> new ArrayList<>()).add(detail);
        }
        long total = 0;
        for (List<Detail> intervals : resources.values()) {
            intervals.sort(Comparator.comparing(Detail::getWindowStart));
            LocalDateTime start = intervals.get(0).getWindowStart();
            LocalDateTime end = intervals.get(0).getWindowEnd();
            for (int i = 1; i < intervals.size(); i++) {
                Detail next = intervals.get(i);
                if (!next.getWindowStart().isAfter(end)) {
                    if (next.getWindowEnd().isAfter(end)) {
                        end = next.getWindowEnd();
                    }
                } else {
                    total = Math.addExact(total, micros(start, end));
                    start = next.getWindowStart();
                    end = next.getWindowEnd();
                }
            }
            total = Math.addExact(total, micros(start, end));
        }
        setter.accept(metrics, seconds(total));
    }
}
