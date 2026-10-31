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
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class MesReportingAnalysisQueryService {

    public static final String TOOL_CODE = "MES_REPORTING_ANALYSIS_QUERY@1";
    private static final int MAX_RANGE_DAYS = 31;
    private static final String UNKNOWN = "UNKNOWN";

    private final MesActualFactoryAccessService factoryAccess;
    private final MesTaskReportService reportService;
    private final MesReportQuantityEvidenceReader quantityReader;
    // 与原来源核定相同：纠正后有效量优先，已纠正但缺有效量不能回退到原始数量。
    private static final String EFFECTIVE_QUANTITY = "CASE WHEN status='REVERSED' THEN 0 WHEN effective_report_num IS NOT NULL THEN effective_report_num WHEN corrected_report_num IS NULL OR corrected_report_num=0 THEN report_num ELSE NULL END";

    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public MesReportingAnalysisDTO.Evidence query(MesReportingAnalysisDTO.Query query) {
        String actualFactory = factoryAccess.require("read");
        if (query == null || !actualFactory.equals(query.getFactoryCode())) {
            throw new CheckedException("报工分析查询工厂与当前实际工厂不一致");
        }
        query.setTaskCode(StringUtils.hasText(query.getTaskCode()) ? query.getTaskCode().trim() : null);
        query.setProduceTaskCode(StringUtils.hasText(query.getProduceTaskCode()) ? query.getProduceTaskCode().trim() : null);
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
        var result = new MesReportingAnalysisDTO.Evidence()
                .setTaskCode(query.getTaskCode())
                .setToolCode(TOOL_CODE)
                .setQueryScope(range.allTime() ? "TASK_LIFECYCLE" : "TIME_RANGE")
                .setMetricDefinition(range.allTime()
                        ? "按指定工单或工序任务的完整生命周期统计；有效报工包含历史空状态和EFFECTIVE，排除REVERSED。产出使用纠正后当前有效量，ROUTE_RETURN不重复计产出。不同工序不可累加当作成品量，数量仍按冻结单位分组。"
                        : "按报工时间落入查询区间统计；有效报工包含历史空状态和EFFECTIVE，排除REVERSED；产出使用纠正后有效量且排除ROUTE_RETURN，各工序发生量不等于成品量，按冻结单位分别展示。")
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
        if (range.allTime() && StringUtils.hasText(query.getTaskCode()) && !StringUtils.hasText(query.getProduceTaskCode())) {
            try {
                result.setQuantityIntegrity(quantityReader.read(actualFactory, query.getTaskCode().trim()));
            } catch (RuntimeException ex) {
                log.warn("报工数量来源取证未完成，factoryCode={}, taskCode={}", actualFactory, query.getTaskCode(), ex);
                result.setQuantityIntegrity(new com.kitex.mom.mes.agent.dto.MesReportQuantityConsistencyDTO.Evidence()
                        .setVersion(MesReportQuantityEvidenceReader.VERSION).setFactoryCode(actualFactory).setTaskCode(query.getTaskCode().trim())
                        .setStatus("DATA_GAP").setScopeComplete(false).setChecks(List.of()).setReports(List.of()).setAllocations(List.of())
                        .setDataGaps(List.of(ex instanceof CheckedException && ex.getMessage() != null ? ex.getMessage()
                                : "数量来源核验未完成，请检查原来源核定、入库账及业务权限")).setEvidenceRefs(List.of()));
            }
        }
        return result;
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
        String released = "CASE WHEN quality_quantity_status='NOT_REQUIRED' THEN " + EFFECTIVE_QUANTITY + " ELSE quality_released_num END";
        List<Map<String, Object>> rows = reportService.getBaseMapper().selectMaps(
                effective(base(query, range))
                        .and(kind -> kind.isNull("execution_kind").or().eq("execution_kind", "NORMAL").or().eq("execution_kind", ""))
                        .select("task_code", "produce_task_code", "COALESCE(NULLIF(report_unit, ''), 'UNKNOWN') AS report_unit",
                                "SUM(report_num) AS original_quantity", "SUM(corrected_report_num) AS corrected_quantity",
                                "COUNT(*) FILTER (WHERE corrected_report_num IS NULL) AS missing_correction_count",
                                "COUNT(*) FILTER (WHERE (" + EFFECTIVE_QUANTITY + ") IS NULL) AS unverified_count",
                                "SUM(" + EFFECTIVE_QUANTITY + ") AS reported_quantity",
                                "COUNT(*) FILTER (WHERE (" + released + ") IS NULL) AS missing_quality_count",
                                "SUM(" + released + ") AS released_quantity",
                                "COUNT(*) FILTER (WHERE quality_quantity_status IS DISTINCT FROM 'NOT_REQUIRED' AND quality_pending_num IS NULL) AS missing_pending_count",
                                "COUNT(*) FILTER (WHERE quality_quantity_status IS DISTINCT FROM 'NOT_REQUIRED' AND quality_rejected_num IS NULL) AS missing_rejected_count",
                                "SUM(CASE WHEN quality_quantity_status='NOT_REQUIRED' THEN 0 ELSE quality_pending_num END) AS pending_quantity",
                                "SUM(CASE WHEN quality_quantity_status='NOT_REQUIRED' THEN 0 ELSE quality_rejected_num END) AS rejected_quantity")
                        .groupBy("task_code", "produce_task_code", "COALESCE(NULLIF(report_unit, ''), 'UNKNOWN')")
                        .orderByAsc("task_code", "produce_task_code", "COALESCE(NULLIF(report_unit, ''), 'UNKNOWN')"));
        return rows.stream().map(row -> {
            long unverified = number(row.get("unverified_count")).longValue();
            return new MesReportingAnalysisDTO.UnitQuantitySummary().setTaskCode(text(row.get("task_code")))
                    .setProduceTaskCode(text(row.get("produce_task_code"))).setReportUnit(text(row.get("report_unit")))
                    .setOriginalQuantity(nullableDecimal(row.get("original_quantity")))
                    .setCorrectedQuantity(number(row.get("missing_correction_count")).longValue() > 0 ? null : nullableDecimal(row.get("corrected_quantity")))
                    .setUnverifiedReportCount(unverified)
                    .setReportedQuantity(unverified > 0 ? null : nullableDecimal(row.get("reported_quantity")))
                    .setQualityReleasedQuantity(number(row.get("missing_quality_count")).longValue() > 0 ? null : nullableDecimal(row.get("released_quantity")))
                    .setQualityPendingQuantity(number(row.get("missing_pending_count")).longValue() > 0 ? null : nullableDecimal(row.get("pending_quantity")))
                    .setQualityRejectedQuantity(number(row.get("missing_rejected_count")).longValue() > 0 ? null : nullableDecimal(row.get("rejected_quantity")));
        }).toList();
    }

    private BigDecimal nullableDecimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
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
                .setOriginalQuantity(item.getReportNum()).setCorrectedQuantity(item.getCorrectedReportNum()).setExecutionKind(item.getExecutionKind())
                .setReportQuantity(currentOutput(item))
                .setReportUnit(item.getReportUnit())
                .setQualityReleasedQuantity("NOT_REQUIRED".equals(item.getQualityQuantityStatus()) || "REVERSED".equals(item.getStatus())
                        ? currentOutput(item) : item.getQualityReleasedNum())
                .setQualityPendingQuantity("NOT_REQUIRED".equals(item.getQualityQuantityStatus()) || "REVERSED".equals(item.getStatus())
                        ? BigDecimal.ZERO : item.getQualityPendingNum())
                .setQualityRejectedQuantity("NOT_REQUIRED".equals(item.getQualityQuantityStatus()) || "REVERSED".equals(item.getStatus())
                        ? BigDecimal.ZERO : item.getQualityRejectedNum())
                .setQualityQuantityStatus(item.getQualityQuantityStatus())
                .setReportTime(item.getReportTime())
                .setWorkerCode(item.getWorkerCode())
                .setWorkerName(item.getWorkerName());
    }

    private BigDecimal currentOutput(MesTaskReportEntity report) {
        if ("ROUTE_RETURN".equals(report.getExecutionKind()) || "REVERSED".equals(report.getStatus())) return BigDecimal.ZERO;
        if (report.getEffectiveReportNum() != null) return report.getEffectiveReportNum();
        return report.getCorrectedReportNum() == null || report.getCorrectedReportNum().signum() == 0 ? report.getReportNum() : null;
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
        if (quantities.stream().anyMatch(row -> row.getUnverifiedReportCount() != null && row.getUnverifiedReportCount() > 0)) {
            gaps.add("存在已纠正但当前有效数量未知的报工，未以原始量或零替代");
        }
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
