package com.kitex.mom.mes.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.agent.dto.MesReportingAnalysisDTO;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.enums.ReportStatusEnum;
import com.kitex.mom.mes.service.MesTaskReportService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 面向智能体的 MES 报工与质量数量语义查询。
 *
 * <p>仅统计真实报工流水。有效数量排除已冲销记录；不同单位绝不直接相加，质量放行、
 * 待判和拒绝数量按报工冻结单位分别返回，防止模型生成失真的“总产量”。</p>
 */
@Service
@RequiredArgsConstructor
public class MesReportingAnalysisQueryService {

    public static final String TOOL_CODE = "MES_REPORTING_ANALYSIS_QUERY@1";
    private static final int MAX_RANGE_DAYS = 31;
    private static final String UNKNOWN = "UNKNOWN";

    private final MesActualFactoryAccessService factoryAccess;
    private final MesTaskReportService reportService;

    public MesReportingAnalysisDTO.Evidence query(MesReportingAnalysisDTO.Query query) {
        String actualFactory = factoryAccess.require("read");
        if (query == null || !actualFactory.equals(query.getFactoryCode())) {
            throw new CheckedException("报工分析查询工厂与当前实际工厂不一致");
        }
        QueryRange range = normalize(query);
        LocalDateTime observedAt = LocalDateTime.now();

        long effectiveCount = reportService.count(effective(base(query, range)));
        long reversedCount = reportService.count(base(query, range)
                .eq("status", ReportStatusEnum.REVERSED.name()));
        List<Map<String, Object>> sourceRows = reportService.getBaseMapper().selectMaps(
                effective(base(query, range))
                        .select("COALESCE(NULLIF(type, ''), 'UNKNOWN') AS source_type", "COUNT(*) AS report_count")
                        .groupBy("COALESCE(NULLIF(type, ''), 'UNKNOWN')"));
        List<Map<String, Object>> taskRows = reportService.getBaseMapper().selectMaps(
                effective(base(query, range)).select("COUNT(DISTINCT task_code) AS task_count"));
        List<MesReportingAnalysisDTO.ModeSummary> modes = queryModes(query, range);
        List<MesReportingAnalysisDTO.UnitQuantitySummary> quantities = queryQuantities(query, range);
        List<MesTaskReportEntity> recent = reportService.list(base(query, range)
                .orderByDesc("report_time").orderByDesc("id").last("LIMIT " + query.getLimit()));

        MesReportingAnalysisDTO.Summary summary = new MesReportingAnalysisDTO.Summary()
                .setEffectiveReportCount(effectiveCount)
                .setReversedReportCount(reversedCount)
                .setAutomaticReportCount(sourceCount(sourceRows, "AUTO"))
                .setManualReportCount(sourceCount(sourceRows, "MANUAL"))
                .setUnknownSourceCount(sourceRows.stream()
                        .filter(row -> !List.of("AUTO", "MANUAL").contains(text(row.get("source_type")).toUpperCase(Locale.ROOT)))
                        .mapToLong(row -> number(row.get("report_count")).longValue()).sum())
                .setDistinctTaskCount(taskRows.isEmpty() ? 0L : number(taskRows.get(0).get("task_count")).longValue());

        List<MesReportingAnalysisDTO.RecentReport> details = recent.stream().map(this::toRecentReport).toList();
        List<String> gaps = dataGaps(modes, quantities, summary);
        List<String> refs = recent.stream()
                .map(item -> "MES:mes_task_report:" + (StringUtils.hasText(item.getRequestId())
                        ? item.getRequestId() : item.getId()))
                .toList();
        return new MesReportingAnalysisDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setQueryScope(range.allTime() ? "TASK_LIFECYCLE" : "TIME_RANGE")
                .setMetricDefinition(range.allTime()
                        ? "按指定工单或工序任务的完整生命周期统计；有效报工包含历史空状态和EFFECTIVE，排除REVERSED冲销记录。报工量及质量数量按报工冻结单位分组，不跨单位求和。"
                        : "按报工时间落入查询区间统计；有效报工包含历史空状态和EFFECTIVE，排除REVERSED冲销记录。报工量、质量放行量、待判量和拒绝量均按报工时冻结单位分组，不跨单位求和。")
                .setFactoryCode(actualFactory)
                .setRangeStart(range.start())
                .setRangeEnd(range.end())
                .setObservedAt(observedAt)
                .setSummary(summary)
                .setModeSummaries(modes)
                .setQuantitySummaries(quantities)
                .setRecentReports(details)
                .setDataGaps(gaps)
                .setEvidenceRefs(refs)
                .setTruncated(effectiveCount + reversedCount > details.size());
    }

    private List<MesReportingAnalysisDTO.ModeSummary> queryModes(MesReportingAnalysisDTO.Query query,
                                                                 QueryRange range) {
        List<Map<String, Object>> rows = reportService.getBaseMapper().selectMaps(
                effective(base(query, range))
                        .select("COALESCE(NULLIF(report_mode, ''), 'UNKNOWN') AS report_mode", "COUNT(*) AS report_count")
                        .groupBy("COALESCE(NULLIF(report_mode, ''), 'UNKNOWN')")
                        .orderByDesc("COUNT(*)"));
        return rows.stream().map(row -> {
            String code = text(row.get("report_mode"));
            return new MesReportingAnalysisDTO.ModeSummary()
                    .setReportMode(code)
                    .setReportModeName(reportModeName(code))
                    .setReportCount(number(row.get("report_count")).longValue());
        }).toList();
    }

    private List<MesReportingAnalysisDTO.UnitQuantitySummary> queryQuantities(
            MesReportingAnalysisDTO.Query query, QueryRange range) {
        List<Map<String, Object>> rows = reportService.getBaseMapper().selectMaps(
                effective(base(query, range))
                        .select("COALESCE(NULLIF(report_unit, ''), 'UNKNOWN') AS report_unit",
                                "SUM(COALESCE(report_num, 0)) AS reported_quantity",
                                "SUM(COALESCE(quality_released_num, 0)) AS released_quantity",
                                "SUM(COALESCE(quality_pending_num, 0)) AS pending_quantity",
                                "SUM(COALESCE(quality_rejected_num, 0)) AS rejected_quantity")
                        .groupBy("COALESCE(NULLIF(report_unit, ''), 'UNKNOWN')")
                        .orderByAsc("COALESCE(NULLIF(report_unit, ''), 'UNKNOWN')"));
        return rows.stream().map(row -> new MesReportingAnalysisDTO.UnitQuantitySummary()
                        .setReportUnit(text(row.get("report_unit")))
                        .setReportedQuantity(decimal(row.get("reported_quantity")))
                        .setQualityReleasedQuantity(decimal(row.get("released_quantity")))
                        .setQualityPendingQuantity(decimal(row.get("pending_quantity")))
                        .setQualityRejectedQuantity(decimal(row.get("rejected_quantity"))))
                .toList();
    }

    private QueryWrapper<MesTaskReportEntity> base(MesReportingAnalysisDTO.Query query, QueryRange range) {
        return new QueryWrapper<MesTaskReportEntity>()
                .eq("factory_code", query.getFactoryCode())
                .ge(!range.allTime(), "report_time", range.start())
                .lt(!range.allTime(), "report_time", range.end())
                .eq(StringUtils.hasText(query.getTaskCode()), "task_code", query.getTaskCode())
                .eq(StringUtils.hasText(query.getProduceTaskCode()), "produce_task_code", query.getProduceTaskCode());
    }

    private QueryWrapper<MesTaskReportEntity> effective(QueryWrapper<MesTaskReportEntity> wrapper) {
        return wrapper.and(status -> status.isNull("status")
                .or().eq("status", ReportStatusEnum.EFFECTIVE.name()));
    }

    private MesReportingAnalysisDTO.RecentReport toRecentReport(MesTaskReportEntity item) {
        return new MesReportingAnalysisDTO.RecentReport()
                .setReportId(item.getId())
                .setRequestId(item.getRequestId())
                .setStatus(item.getStatus())
                .setTaskCode(item.getTaskCode())
                .setProduceTaskCode(item.getProduceTaskCode())
                .setMaterialCode(item.getMatCode())
                .setMaterialName(item.getMatName())
                .setReportMode(item.getReportMode())
                .setSourceType(item.getType())
                .setReportQuantity(item.getReportNum())
                .setReportUnit(item.getReportUnit())
                .setQualityReleasedQuantity(item.getQualityReleasedNum())
                .setQualityPendingQuantity(item.getQualityPendingNum())
                .setQualityRejectedQuantity(item.getQualityRejectedNum())
                .setQualityQuantityStatus(item.getQualityQuantityStatus())
                .setReportTime(item.getReportTime())
                .setWorkerCode(item.getWorkerCode())
                .setWorkerName(item.getWorkerName());
    }

    private QueryRange normalize(MesReportingAnalysisDTO.Query query) {
        if (Boolean.TRUE.equals(query.getAllTime())) {
            if (!StringUtils.hasText(query.getTaskCode())
                    && !StringUtils.hasText(query.getProduceTaskCode())) {
                throw new CheckedException("报工分析全生命周期查询必须指定工单或工序任务编码");
            }
            int limit = query.getLimit() == null ? 20 : query.getLimit();
            query.setLimit(Math.max(1, Math.min(limit, 50)));
            return new QueryRange(null, null, true);
        }
        LocalDate today = LocalDate.now();
        LocalDateTime start = query.getStartTime() == null ? today.atStartOfDay() : query.getStartTime();
        LocalDateTime end = query.getEndTime() == null ? today.plusDays(1).atStartOfDay() : query.getEndTime();
        if (!end.isAfter(start)) {
            throw new CheckedException("报工分析查询结束时间必须晚于开始时间");
        }
        if (end.isAfter(start.plusDays(MAX_RANGE_DAYS))) {
            throw new CheckedException("报工分析单次查询区间不能超过31天");
        }
        int limit = query.getLimit() == null ? 20 : query.getLimit();
        query.setLimit(Math.max(1, Math.min(limit, 50)));
        return new QueryRange(start, end, false);
    }

    private List<String> dataGaps(List<MesReportingAnalysisDTO.ModeSummary> modes,
                                  List<MesReportingAnalysisDTO.UnitQuantitySummary> quantities,
                                  MesReportingAnalysisDTO.Summary summary) {
        List<String> gaps = new ArrayList<>();
        modes.stream().filter(item -> UNKNOWN.equals(item.getReportMode())).findFirst()
                .ifPresent(item -> gaps.add(item.getReportCount() + "条有效报工缺少冻结报工模式"));
        quantities.stream().filter(item -> UNKNOWN.equals(item.getReportUnit())).findFirst()
                .ifPresent(item -> gaps.add("存在缺少冻结单位的历史报工，其数量只在UNKNOWN组展示，不能与其他单位合并"));
        if (summary.getUnknownSourceCount() > 0) {
            gaps.add(summary.getUnknownSourceCount() + "条有效报工无法识别为自动或人工来源");
        }
        return gaps;
    }

    private long sourceCount(List<Map<String, Object>> rows, String source) {
        return rows.stream().filter(row -> source.equalsIgnoreCase(text(row.get("source_type"))))
                .mapToLong(row -> number(row.get("report_count")).longValue()).sum();
    }

    private String reportModeName(String code) {
        if (UNKNOWN.equals(code)) return "历史未冻结";
        try {
            return ReportModeEnum.fromCode(code).getName();
        } catch (IllegalArgumentException ignored) {
            return "未识别模式";
        }
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private Number number(Object value) {
        return value instanceof Number number ? number : 0L;
    }

    private BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private record QueryRange(LocalDateTime start, LocalDateTime end, boolean allTime) {
    }
}
