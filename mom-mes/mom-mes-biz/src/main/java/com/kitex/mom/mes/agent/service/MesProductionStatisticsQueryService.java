package com.kitex.mom.mes.agent.service;

import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.agent.dto.MesProductionStatisticsDTO.*;
import com.kitex.mom.mes.mapper.agent.MesProductionStatisticsMapper;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 真实生产统计的固定只读合同。SQL先聚合全部命中再限返排名，明细上限不参与统计。
 * 派工班次和车间来自原派工事实；没有冻结事实的产线、班组不在此合同中。
 */
@Service
@RequiredArgsConstructor
public class MesProductionStatisticsQueryService {
    public static final String TOOL_CODE = "MES_PRODUCTION_STATISTICS_QUERY@1";
    private final MesActualFactoryAccessService factoryAccess;
    private final MesProductionStatisticsMapper mapper;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Evidence query(Query query) {
        String factory = factoryAccess.require("read");
        require(query != null && factory.equals(query.getFactoryCode()), "生产统计查询工厂与当前实际工厂不一致");
        validate(query);
        Long tenant = TenantContextHolder.getTenantId();
        LocalDateTime observed = LocalDateTime.now();
        List<String> gaps = new ArrayList<>();
        WindowStatistics current = collect(tenant, query, query.getStartTime(), query.getEndTime(), observed, gaps, "本窗");
        WindowStatistics previous = query.getComparisonKind() == null ? null
                : collect(tenant, query, query.getComparisonStart(), query.getComparisonEnd(), observed, gaps, "对比窗");
        List<Map<String, Object>> rankRows = query.getRankGroupBy() == null ? List.of()
                : mapper.ranking(tenant, query, query.getStartTime(), query.getEndTime());
        List<Ranking> ranking = new ArrayList<>();
        for (Map<String, Object> row : rankRows) {
            String code = text(row.get("group_code"));
            BigDecimal value = decimal(row.get("metric_value"));
            if ("UNKNOWN".equals(code) || value == null)
                gaps.add("排名存在归属或指标缺失组，不能把已知组当成完整正常结论");
            ranking.add(new Ranking().setRank(ranking.size() + 1).setGroupCode(code).setGroupName(code)
                    .setValue(value).setMaterialCode(query.getMaterialCode()).setReportUnit(query.getReportUnit()));
        }
        return new Evidence().setToolCode(TOOL_CODE).setFactoryCode(factory).setScope(query)
                .setObservedAt(observed).setSummary(current).setComparison(previous)
                .setDeltas(previous == null ? List.of() : deltas(current, previous))
                .setRanking(ranking).setRankingPopulationCount(rankRows.isEmpty() ? 0L : number(rankRows.get(0), "population_count"))
                .setRankingMetricScope("MATCHED").setTruncated(false)
                .setMetricDefinition("所有计数和排名均由当前租户、当前授权实际工厂内全部未删除命中事实聚合。"
                        + "工单范围按计划或实际执行半开区间与窗口交集选择，指定工单仍应用日期；恰在窗口开始结束的工单不计入该窗口，无有效起止依据的工单不进入时间窗。"
                        + "工单车间取工单冻结车间、班次取原派工存在事实；报工单位仅筛选报工事件及其指标，不筛选没有报工单位字段的工单计数。"
                        + "工单状态、暂停和延期均为采集时点状态，不是历史状态快照或历史产量。"
                        + "报工实绩按report_time开始含、结束不含；有效记录为历史空状态或EFFECTIVE，REVERSED单独计数。"
                        + "报工量优先使用受控纠正后的effective_report_num，历史回退report_num；质量数量为当前同步结果。"
                        + "数量按物料与报工冻结单位分别返回；缺量保持空。派工车间/班次必须由原arrange_code唯一关联，不使用当前主数据猜测。"
                        + "排名先全量聚合再排序限返，同值按编码稳定排序，排名序号不表示并列名次。"
                        + "比较只对相同指标、物料和单位计算差值，零基数或缺数的变化率为空。")
                .setDataGaps(new ArrayList<>(new LinkedHashSet<>(gaps)))
                .setEvidenceRefs(List.of("MES:mes_task:authorized-aggregate", "MES:mes_task_report:authorized-aggregate", "MES:mes_task_arrange:exact-attribution"));
    }

    private WindowStatistics collect(Long tenant, Query q, LocalDateTime start, LocalDateTime end,
                                     LocalDateTime observed, List<String> gaps, String label) {
        Map<String, Object> tasks = mapper.taskSummary(tenant, q, start, end, observed);
        Map<String, Object> reports = mapper.reportSummary(tenant, q, start, end);
        if (number(tasks, "unknown_status_count") > 0 || number(reports, "unknown_status_count") > 0)
            gaps.add(label + "存在未识别工单/报工状态，已知状态计数不可解释为全部对象正常");
        long unattributed = mapper.unattributed(tenant, q, start, end);
        if (unattributed > 0 && (q.getWorkshopCode() != null || q.getShiftCode() != null
                || Set.of("WORKSHOP", "SHIFT").contains(Objects.toString(q.getRankGroupBy(), "")))) {
            gaps.add(label + "有" + unattributed + "条基础范围报工缺少唯一原派工车间/班次归属，无法纳入该归属筛选或排名；缺口不是零产量");
        }
        List<QuantitySummary> quantities = mapper.quantities(tenant, q, start, end).stream().map(row -> {
            QuantitySummary result = new QuantitySummary().setMaterialCode(text(row.get("material_code")))
                    .setReportUnit(text(row.get("report_unit"))).setReportedQuantity(decimal(row.get("reported_quantity")))
                    .setQualityReleasedQuantity(decimal(row.get("released_quantity")))
                    .setQualityPendingQuantity(decimal(row.get("pending_quantity")))
                    .setQualityRejectedQuantity(decimal(row.get("rejected_quantity")))
                    .setIncompleteQuantityCount(number(row, "incomplete_count"));
            if ("UNKNOWN".equals(result.getMaterialCode()) || "UNKNOWN".equals(result.getReportUnit())) {
                // 多条未知物料/单位不一定属于同一物理量，不能因聚在UNKNOWN组就给出可加总数量。
                result.setReportedQuantity(null).setQualityReleasedQuantity(null)
                        .setQualityPendingQuantity(null).setQualityRejectedQuantity(null);
                gaps.add(label + "存在未冻结物料或单位的报工，UNKNOWN组数量不具备可比口径并保持空，不能合并数量");
            }
            if (result.getIncompleteQuantityCount() > 0)
                gaps.add(label + "存在缺失或非法数量，受影响数量保持空，不能按零或正常处理");
            return result;
        }).toList();
        return new WindowStatistics().setRangeStart(start).setRangeEnd(end)
                .setMatchedTaskCount(number(tasks, "matched_task_count"))
                .setCurrentNotStartedTaskCount(number(tasks, "not_started_count"))
                .setCurrentInProgressTaskCount(number(tasks, "in_progress_count"))
                .setCurrentFinishedTaskCount(number(tasks, "finished_count"))
                .setCurrentPausedTaskCount(number(tasks, "paused_count"))
                .setCurrentDelayedTaskCount(number(tasks, "delayed_count"))
                .setEffectiveReportCount(number(reports, "effective_count"))
                .setReversedReportCount(number(reports, "reversed_count"))
                .setDistinctReportedTaskCount(number(reports, "distinct_task_count"))
                .setUnattributedReportCount(unattributed).setQuantitySummaries(quantities);
    }

    private List<Delta> deltas(WindowStatistics current, WindowStatistics previous) {
        List<Delta> result = new ArrayList<>();
        result.add(delta("matchedTaskCount", null, null, BigDecimal.valueOf(current.getMatchedTaskCount()), BigDecimal.valueOf(previous.getMatchedTaskCount())));
        result.add(delta("effectiveReportCount", null, null, BigDecimal.valueOf(current.getEffectiveReportCount()), BigDecimal.valueOf(previous.getEffectiveReportCount())));
        result.add(delta("distinctReportedTaskCount", null, null, BigDecimal.valueOf(current.getDistinctReportedTaskCount()), BigDecimal.valueOf(previous.getDistinctReportedTaskCount())));
        Map<List<String>, QuantitySummary> a = groups(current), b = groups(previous);
        Set<List<String>> keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (List<String> key : keys) {
            QuantitySummary left = a.get(key), right = b.get(key);
            // 不存在分组代表该窗无有效事件，缺失数量则保持空，二者不可混淆。
            result.add(delta("reportedQuantity", key.get(0), key.get(1), left == null ? BigDecimal.ZERO : left.getReportedQuantity(), right == null ? BigDecimal.ZERO : right.getReportedQuantity()));
            result.add(delta("qualityRejectedQuantity", key.get(0), key.get(1), left == null ? BigDecimal.ZERO : left.getQualityRejectedQuantity(), right == null ? BigDecimal.ZERO : right.getQualityRejectedQuantity()));
        }
        return result;
    }

    private Map<List<String>, QuantitySummary> groups(WindowStatistics window) {
        Map<List<String>, QuantitySummary> map = new LinkedHashMap<>();
        window.getQuantitySummaries().forEach(row -> map.put(List.of(row.getMaterialCode(), row.getReportUnit()), row));
        return map;
    }

    private Delta delta(String metric, String material, String unit, BigDecimal current, BigDecimal previous) {
        boolean known = current != null && previous != null && !"UNKNOWN".equals(material) && !"UNKNOWN".equals(unit);
        boolean baseline = known && previous.signum() != 0;
        BigDecimal change = known ? current.subtract(previous) : null;
        return new Delta().setMetric(metric).setMaterialCode(material).setReportUnit(unit)
                .setCurrentValue(current).setPreviousValue(previous).setAbsoluteChange(change)
                .setPercentChange(baseline ? change.multiply(BigDecimal.valueOf(100)).divide(previous, 4, RoundingMode.HALF_UP) : null)
                .setStatus(!known ? "MISSING_DATA" : baseline ? "COMPARABLE" : "ZERO_BASELINE");
    }

    private void validate(Query q) {
        for (String value : new String[]{q.getTaskCode(), q.getWorkshopCode(), q.getShiftCode(), q.getMaterialCode()})
            require(value == null || value.matches("[A-Za-z0-9][A-Za-z0-9._/\\-]{1,99}"), "生产统计对象编码无效");
        require(q.getReportUnit() == null || q.getReportUnit().matches("[A-Za-z0-9\\p{IsHan}._/\\-]{1,20}"), "报工冻结单位无效");
        if (Boolean.TRUE.equals(q.getAllTime()))
            require(q.getStartTime() == null && q.getEndTime() == null && q.getComparisonKind() == null, "完整历史统计不能夹带时间或比较窗口");
        else window(q.getStartTime(), q.getEndTime());
        if (q.getComparisonKind() != null) {
            require(Set.of("EXPLICIT", "PREVIOUS_PERIOD", "PREVIOUS_YEAR").contains(q.getComparisonKind()), "比较类型未登记");
            window(q.getComparisonStart(), q.getComparisonEnd());
            if ("PREVIOUS_YEAR".equals(q.getComparisonKind()))
                require(q.getStartTime().minusYears(1).equals(q.getComparisonStart()) && q.getEndTime().minusYears(1).equals(q.getComparisonEnd()), "同比时间窗与冻结类型不一致");
            if ("PREVIOUS_PERIOD".equals(q.getComparisonKind()))
                require(q.getStartTime().equals(q.getComparisonEnd()) && q.getStartTime().minusSeconds(ChronoUnit.SECONDS.between(q.getStartTime(), q.getEndTime())).equals(q.getComparisonStart()), "环比必须为等时长上一窗口");
        } else require(q.getComparisonStart() == null && q.getComparisonEnd() == null, "比较日期缺少明确类型");
        if (q.getRankGroupBy() != null) {
            require(Set.of("WORKSHOP", "SHIFT", "TASK").contains(q.getRankGroupBy())
                    && q.getRankMetric() != null && Set.of("TASK_COUNT", "REPORT_COUNT", "REPORT_QUANTITY", "QUALITY_REJECTED_QUANTITY").contains(q.getRankMetric())
                    && q.getRankDirection() != null && Set.of("ASC", "DESC").contains(q.getRankDirection())
                    && q.getRankLimit() != null && q.getRankLimit() >= 1 && q.getRankLimit() <= 50, "排名必须指定固定分组、指标、方向和1至50返回上限");
            require(!("SHIFT".equals(q.getRankGroupBy()) && "TASK_COUNT".equals(q.getRankMetric())), "工单可能跨班次，班次排名请使用报工实绩指标");
            if (q.getRankMetric().endsWith("QUANTITY"))
                require(q.getMaterialCode() != null && q.getReportUnit() != null, "数量排名须明确物料与报工冻结单位");
        } else
            require(q.getRankMetric() == null && q.getRankDirection() == null && q.getRankLimit() == null, "排名参数不完整");
    }

    private void window(LocalDateTime start, LocalDateTime end) {
        require(start != null && end != null && end.isAfter(start) && !end.isAfter(start.plusDays(366)), "统计窗口必须有效且不能超过366天");
    }

    private long number(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private BigDecimal decimal(Object value) {
        return value == null ? null : value instanceof BigDecimal number ? number : new BigDecimal(value.toString());
    }

    private String text(Object value) {
        return value == null ? "UNKNOWN" : value.toString();
    }

    private void require(boolean value, String message) {
        if (!value) throw new CheckedException(message);
    }
}
