package com.kitex.mom.mes.service.impl;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.common.metrics.MetricFraction;
import com.kitex.mom.mes.dto.MesPlanAttainmentDTO.*;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * MES对象口径只组合分类计数；百分比统一由公共精确比率基础计算。
 */
@Component
public class MesPlanAttainmentCalculator {

    public Summary summary(Aggregate counts, Query query, boolean coverageIncomplete) {
        require(counts != null, "计划聚合不可读取");
        BigDecimal due = count(counts.getDueCount());
        BigDecimal onTime = count(counts.getOnTimeCount());
        BigDecimal late = count(counts.getLateCount());
        BigDecimal open = count(counts.getOpenCount());
        BigDecimal closed = count(counts.getClosedCount());
        BigDecimal draft = count(counts.getDraftCount());
        BigDecimal unknown = count(counts.getUnknownCount());
        BigDecimal completed = onTime.add(late);
        BigDecimal applicable = due.subtract(draft);
        if ("EXCLUDE_CLOSED".equals(query.getClosedPolicy())) {
            applicable = applicable.subtract(closed);
        }
        BigDecimal includedClosed = "INCLUDE_UNFINISHED".equals(query.getClosedPolicy()) ? closed : BigDecimal.ZERO;
        require(applicable.equals(completed.add(open).add(unknown).add(includedClosed)), "计划分类计数不一致");
        boolean complete = unknown.signum() == 0 && !coverageIncomplete;
        return new Summary().setDueCount(due.toPlainString()).setApplicableCount(applicable.toPlainString())
                .setCompletedCount(completed.toPlainString()).setOnTimeCount(onTime.toPlainString())
                .setLateCount(late.toPlainString()).setOpenCount(open.toPlainString()).setClosedCount(closed.toPlainString())
                .setDraftCount(draft.toPlainString()).setUnknownCount(unknown.toPlainString())
                .setCompletionRate(MetricFraction.bounded(completed, applicable, complete))
                .setOnTimeRate(MetricFraction.bounded(onTime, applicable, complete));
    }

    public boolean coverageIncomplete(Query query, Coverage coverage) {
        require(coverage != null, "计划覆盖信息不可读取");
        count(coverage.getMissingDueCount());
        return count(coverage.getUnattributedDueCount()).signum() > 0 && query.getWorkshopCode() != null;
    }

    public List<String> gaps(Query query, Coverage coverage, Summary summary) {
        List<String> gaps = new ArrayList<>();
        if (count(coverage.getMissingDueCount()).signum() > 0) {
            gaps.add("基础筛选范围有" + coverage.getMissingDueCount() + "个非草稿对象缺少计划截止时间，无法归入到期窗口");
        }
        if (count(coverage.getUnattributedDueCount()).signum() > 0) {
            gaps.add("基础范围有" + coverage.getUnattributedDueCount() + "个到期对象冻结车间缺失；覆盖计数未应用车间筛选");
        }
        if (count(summary.getUnknownCount()).signum() > 0) {
            gaps.add("存在未知状态、来源身份或完成时间证据异常；受影响比率不完整，请下钻UNKNOWN");
        }
        gaps.add("当前计划与状态观察，不是历史计划快照；后续修改计划、冲销或重开会改变重新查询结果");
        return gaps;
    }

    public Row row(Fact fact) {
        require(fact != null && fact.getCategory() != null, "计划明细分类不可读取");
        List<String> issues = new ArrayList<>();
        if (fact.getIssue() != null) {
            issues.add(fact.getIssue());
        }
        if (fact.getWorkshopCode() == null) {
            issues.add("MISSING_FROZEN_WORKSHOP");
        }
        return new Row().setObjectId(fact.getObjectId()).setObjectCode(fact.getObjectCode()).setTaskCode(fact.getTaskCode())
                .setWorkshopCode(fact.getWorkshopCode()).setRouteCode(fact.getRouteCode()).setRouteNodeCode(fact.getRouteNodeCode())
                .setStatus(fact.getStatus()).setPlannedEndTime(fact.getPlannedEndTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .setFinishTime(fact.getFinishTime() == null ? null : fact.getFinishTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .setCategory(fact.getCategory()).setIssues(issues);
    }

    private BigDecimal count(String value) {
        require(value != null && value.matches("[0-9]+"), "计划计数缺失或无效，不能按零处理");
        return new BigDecimal(value);
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }
}
