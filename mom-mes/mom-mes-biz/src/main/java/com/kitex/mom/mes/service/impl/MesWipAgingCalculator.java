package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.common.metrics.MetricFraction;
import com.kitex.mom.common.time.ElapsedDuration;
import com.kitex.mom.mes.dto.MesWipAgingDTO.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SQL负责共享分类；历时和有界比率真实调用公共精确基础。
 */
@Component
public class MesWipAgingCalculator {

    private static final Set<String> KNOWN_STATES = Set.of("WAIT", "UN_RECEIVE", "RECEIVE", "PRODUCT", "PAUSE");

    public Summary summary(Aggregate a, LocalDateTime observed, boolean coverageIncomplete) {
        require(a != null, "在制聚合不可读取");
        BigDecimal population = count(a.getPopulationCount());
        BigDecimal waitingDispatch = count(a.getWaitingDispatchCount());
        BigDecimal waitingReceive = count(a.getWaitingReceiveCount());
        BigDecimal received = count(a.getReceivedCount());
        BigDecimal producing = count(a.getProducingCount());
        BigDecimal paused = count(a.getPausedCount());
        BigDecimal unknownStatus = count(a.getUnknownStatusCount());
        BigDecimal sourceIssue = count(a.getSourceIssueCount());
        BigDecimal knownAge = count(a.getKnownQueueAgeCount());
        BigDecimal unknownAge = count(a.getUnknownQueueAgeCount());
        BigDecimal over = count(a.getOverThresholdCount());
        require(population.compareTo(waitingDispatch.add(waitingReceive).add(received).add(producing).add(paused).add(unknownStatus)) == 0
                && population.compareTo(knownAge.add(unknownAge)) == 0 && over.compareTo(knownAge) <= 0
                && sourceIssue.compareTo(unknownAge) <= 0 && unknownStatus.compareTo(unknownAge) <= 0, "在制分类计数不一致");
        require((knownAge.signum() == 0) == (a.getOldestNoticeTime() == null), "已知停留子集最早下达证据不一致");
        ElapsedDuration.Result maximum = a.getOldestNoticeTime() == null ? null : ElapsedDuration.since(a.getOldestNoticeTime(), observed);
        require(maximum == null || "CALCULABLE".equals(maximum.status()), "已知停留子集最早下达证据无效");
        return new Summary().setPopulationCount(text(population)).setWaitingDispatchCount(text(waitingDispatch))
                .setWaitingReceiveCount(text(waitingReceive)).setReceivedCount(text(received)).setProducingCount(text(producing))
                .setPausedCount(text(paused)).setUnknownStatusCount(text(unknownStatus)).setSourceIssueCount(text(sourceIssue))
                .setKnownQueueAgeCount(text(knownAge)).setUnknownQueueAgeCount(text(unknownAge)).setOverThresholdCount(text(over))
                .setOldestNoticeTime(iso(a.getOldestNoticeTime())).setMaxQueueResidenceSeconds(maximum == null ? null : maximum.seconds())
                .setOverThresholdRate(MetricFraction.bounded(over, population, unknownAge.signum() == 0 && !coverageIncomplete));
    }

    public boolean coverageIncomplete(Query q, Coverage coverage) {
        require(coverage != null, "在制覆盖信息不可读取");
        return count(coverage.getUnattributedCount()).signum() > 0 && q.getWorkshopCode() != null;
    }

    public List<String> gaps(Query q, Coverage coverage, Summary summary) {
        List<String> gaps = new ArrayList<>();
        if (count(coverage.getUnattributedCount()).signum() > 0) {
            gaps.add("基础编码范围有" + coverage.getUnattributedCount() + "个当前工序任务缺少冻结车间；覆盖计数未应用车间筛选");
        }
        if (count(summary.getUnknownQueueAgeCount()).signum() > 0) {
            gaps.add("部分任务来源、状态或时间证据异常，停留未知；超阈值率不完整，请下钻UNKNOWN");
        }
        gaps.add("当前观察不是历史快照；计数是工序任务数，不能作为唯一实物件数或数量在制");
        gaps.add("下达后自然停留及首次开工后自然历时包含等待/暂停，不是净加工时间、当前状态持续时间或周转率");
        gaps.add("最早下达与最大停留仅代表停留时间可核定的子集；缺历史平均在制量，未计算周转率");
        return gaps;
    }

    public Row row(Fact fact, LocalDateTime observed) {
        require(fact != null && Set.of("WITHIN", "OVER", "UNKNOWN").contains(safe(fact.getAgingCategory())), "在制明细分类不可读取");
        ElapsedDuration.Result queue = duration(fact.getNoticeTime(), observed, fact.getQueueIssue());
        ElapsedDuration.Result start = fact.getStartTime() == null && fact.getStartIssue() == null ? null
                : duration(fact.getStartTime(), observed, fact.getStartIssue());
        require("UNKNOWN".equals(fact.getAgingCategory()) != "CALCULABLE".equals(queue.status()), "在制明细停留证据不一致");
        return new Row().setObjectId(fact.getObjectId()).setObjectCode(fact.getObjectCode()).setTaskCode(fact.getTaskCode())
                .setWorkshopCode(fact.getWorkshopCode()).setRouteCode(fact.getRouteCode()).setRouteNodeCode(fact.getRouteNodeCode())
                .setMatCode(fact.getMatCode()).setStatus(fact.getStatus()).setNoticeTime(iso(fact.getNoticeTime())).setStartTime(iso(fact.getStartTime()))
                .setQueueResidence(queue).setSinceFirstStart(start).setAgingCategory(fact.getAgingCategory()).setIssues(issues(fact, observed));
    }

    private List<String> issues(Fact f, LocalDateTime observed) {
        Set<String> issues = new LinkedHashSet<>();
        if (f.getSourceIssue() != null) {
            issues.add(f.getSourceIssue());
        }
        if (!KNOWN_STATES.contains(safe(f.getStatus()))) {
            issues.add("UNKNOWN_STATUS");
        }
        if (f.getFinishTime() != null) {
            issues.add("CONTRADICTORY_FINISH_TIME");
        }
        if (f.getStartTime() != null && f.getStartTime().isAfter(observed)) {
            issues.add("FUTURE_START_TIME");
        }
        if (f.getNoticeTime() == null) {
            issues.add("MISSING_NOTICE_TIME");
        }
        if (f.getNoticeTime() != null && f.getNoticeTime().isAfter(observed)) {
            issues.add("FUTURE_NOTICE_TIME");
        }
        if (f.getNoticeTime() != null && f.getStartTime() != null && f.getNoticeTime().isAfter(f.getStartTime())) {
            issues.add("INVERTED_NOTICE_START");
        }
        if (f.getStartTime() == null && Set.of("PRODUCT", "PAUSE").contains(safe(f.getStatus()))) {
            issues.add("MISSING_START_TIME");
        }
        if (f.getWorkshopCode() == null) {
            issues.add("MISSING_FROZEN_WORKSHOP");
        }
        return new ArrayList<>(issues);
    }

    private ElapsedDuration.Result duration(LocalDateTime origin, LocalDateTime observed, String issue) {
        return issue == null ? ElapsedDuration.since(origin, observed) : new ElapsedDuration.Result("INCOMPLETE", null, issue);
    }

    private BigDecimal count(String value) {
        require(value != null && value.matches("0|[1-9][0-9]*"), "在制计数缺失或无效，不能按零处理");
        return new BigDecimal(value);
    }

    private String text(BigDecimal value) {
        return value.toPlainString();
    }

    private String iso(LocalDateTime value) {
        return value == null ? null : value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }
}
