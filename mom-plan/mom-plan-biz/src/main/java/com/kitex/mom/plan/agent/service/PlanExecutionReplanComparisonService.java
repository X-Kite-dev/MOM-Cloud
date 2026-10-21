package com.kitex.mom.plan.agent.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.mom.plan.agent.dto.PlanExecutionReplanComparisonDTO.Comparison;
import com.kitex.mom.plan.agent.dto.PlanExecutionReplanComparisonDTO.Metrics;
import com.kitex.mom.plan.agent.dto.PlanExecutionReplanComparisonDTO.Operation;
import com.kitex.mom.plan.dto.PlanResourceLocalReplanDTO;
import com.kitex.mom.plan.dto.PlnSchedulingEffectReviewDTO;
import com.kitex.mom.plan.entity.PlnResourceReplanCaseEntity;
import com.kitex.mom.plan.entity.PlnSchedulingEffectObservationEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 原计划与重排计划的确定性同事实对照服务，不负责CRUD或模型推理。
 *
 * <p>只读取调用方已经完成权限和发布链校验的案件，以及指定请求对应的不可变观察，
 * 不重新查询MES，也不把其他时间点的事实拼接到本次对照。仅比较真正移动的工序，
 * 以数量、序号、计划时间和资源核对阻断快照漂移；数据不完整时显式排除。</p>
 */
@Service
@RequiredArgsConstructor
public class PlanExecutionReplanComparisonService {

    private static final int DISPLAY_LIMIT = 100;
    private static final String FORMULA_VERSION = "SAME_FACT_SCHEDULE_ALIGNMENT_V1";
    private static final String INTERPRETATION = "只比较本次移动工序，同一不可变MES观察分别对照原计划和重排计划；"
            + "两组分母严格相同，缺口工序共同排除。迟开/完工只累计正向延误，提前不抵扣；"
            + "遵从率按绝对偏差和共同容差计算，无实际开/完工样本为未知。计划偏移正数代表延期；"
            + "重新设置目标可能降低迟延或提高遵从率，这不是生产效率提高或重排因果收益。";

    private final ObjectMapper objectMapper;

    /** 找到唯一选中案件和此次请求的观察，不允许用最新观察代替幂等请求的原始事实。 */
    public Comparison compare(PlanResourceLocalReplanDTO.Scenario scenario,
                              PlnSchedulingEffectReviewDTO.Detail detail, String requestId) {
        var selected = scenario.getCandidates().stream()
                .filter(candidate -> Objects.equals(candidate.getProposalTaskId(),
                        scenario.getScenario().getSelectedTaskId())).toList();
        if (selected.size() != 1) {
            throw new IllegalArgumentException("重排效果对照缺少唯一选中案件");
        }
        var observations = detail.getObservations().stream()
                .filter(item -> Objects.equals(requestId, item.getRequestId())
                        && Objects.equals(item.getScenarioId(), scenario.getScenario().getId())).toList();
        if (observations.size() != 1) {
            throw new IllegalArgumentException("重排效果对照找不到此次请求的唯一不可变观察");
        }
        return compare(selected.get(0), observations.get(0), detail.getReview().getPlannedScopeSnapshot());
    }

    /** 汇总全范围，逐行展示最多100条；任何解析/全局来源缺口都不能输出虚假的零指标。 */
    private Comparison compare(PlnResourceReplanCaseEntity selected,
                               PlnSchedulingEffectObservationEntity observation, String plannedScopeSnapshot) {
        List<String> gaps = new ArrayList<>();
        List<Operation> rows = new ArrayList<>();
        Aggregate original = new Aggregate();
        Aggregate replanned = new Aggregate();
        int scopeCount = Objects.requireNonNullElse(selected.getMovedOperationCount(), 0);
        LocalDateTime observedAt = observation.getObservedTime();
        try {
            JsonNode actualSnapshot = read(observation.getActualSnapshot());
            // observation.observedTime是采集开始，capturedTime才是远程读取完成后的事实快照时刻。
            observedAt = time(actualSnapshot, "capturedTime");
            JsonNode proposed = read(selected.getProposedScheduleSnapshot());
            JsonNode before = read(selected.getOriginalScheduleSnapshot());
            if (!proposed.isArray() || proposed.isEmpty() || !before.isArray()) {
                throw new IllegalArgumentException("原计划或移动工序冻结快照缺失");
            }
            scopeCount = proposed.size();
            Map<String, JsonNode> proposedRows = index(proposed);
            Map<String, JsonNode> originalRows = index(before);
            Map<String, JsonNode> plannedRows = index(read(plannedScopeSnapshot).path("operations"));
            JsonNode actualRows = actualSnapshot.path("operations");
            Map<String, JsonNode> actualByKey = index(actualRows);
            if (!List.of("WAITING_EXECUTION", "IN_PROGRESS", "COMPLETED")
                    .contains(observation.getObservationStatus())
                    || hasItems(actualSnapshot.path("sourceErrors"))
                    || hasItems(actualSnapshot.path("mappingGaps"))) {
                throw new IllegalArgumentException("本次MES观察尚未派工、存在来源/映射缺口或已回退："
                        + observation.getObservationStatus());
            }
            if (scopeCount != Objects.requireNonNullElse(selected.getMovedOperationCount(), -1)
                    // PLAN候选含未移动工序，但本次对照只能投影到实际发布的移动范围。
                    || !actualByKey.keySet().containsAll(proposedRows.keySet())
                    || !plannedRows.keySet().containsAll(proposedRows.keySet())
                    || observation.getToleranceMinutes() == null || observation.getToleranceMinutes() < 0
                    || observedAt == null
                    || StrUtil.isBlank(selected.getOriginalScheduleFingerprint())
                    || StrUtil.isBlank(selected.getProposalFingerprint())
                    || StrUtil.isBlank(observation.getActualFingerprint())
                    || !Objects.equals(selected.getFactoryCode(), observation.getFactoryCode())) {
                throw new IllegalArgumentException("移动工序数量、MES观察范围或共同观察口径不一致");
            }
            for (var entry : proposedRows.entrySet()) {
                JsonNode after = entry.getValue();
                JsonNode old = originalRows.get(entry.getKey());
                JsonNode actual = actualByKey.get(entry.getKey());
                String gap = validate(old, after, actual, plannedRows.get(entry.getKey()), observedAt);
                if (gap != null) {
                    gaps.add(text(after, "issueNo") + "/" + text(after, "processCode") + "：" + gap);
                } else {
                    original.add(time(old, "scheduledStartTime"), time(old, "scheduledEndTime"), actual,
                            observedAt, observation.getToleranceMinutes());
                    replanned.add(time(after, "scheduledStartTime"), time(after, "scheduledEndTime"), actual,
                            observedAt, observation.getToleranceMinutes());
                }
                rows.add(row(old, after, actual, gap));
            }
        } catch (Exception exception) {
            // 全局缺口必须使全部汇总未知；不保留部分已计算指标作为完整结论。
            original = new Aggregate();
            replanned = new Aggregate();
            rows.clear();
            gaps.add("停止对照：" + exception.getMessage());
        }
        String status = original.count == 0 ? "DATA_GAP"
                : original.count < scopeCount ? "PARTIAL"
                : original.started == 0 ? "WAITING_EXECUTION" : "COMPARABLE";
        String summary = "移动工序" + scopeCount + "道，共同可比较" + original.count + "道，排除"
                + (scopeCount - original.count) + "道；";
        summary += original.count == 0 ? "证据不足，旧、新指标均未知"
                : (original.finished == 0 ? "尚无实际完工样本，迟完工指标未知"
                : "同事实迟完工总分钟：原计划" + original.finishLate + "，重排" + replanned.finishLate)
                + "；逾期未开工：原计划" + original.pending + "，重排" + replanned.pending
                + "。仅表示目标对齐变化，不代表效率提升";
        return new Comparison(FORMULA_VERSION, status, selected.getScenarioId(), selected.getId(),
                observation.getId(), observation.getRequestId(), observedAt, observation.getActualFingerprint(),
                selected.getOriginalScheduleFingerprint(), selected.getProposalFingerprint(),
                observation.getToleranceMinutes(), scopeCount, original.count, scopeCount - original.count,
                original.count == 0 ? null : original.metrics(), replanned.count == 0 ? null : replanned.metrics(),
                rows.stream().limit(DISPLAY_LIMIT).toList(), rows.size() > DISPLAY_LIMIT,
                gaps, summary, INTERPRETATION);
    }

    /** 唯一键沿用PLAN/MES正式映射：计划号+工序编码；重复键故障关闭，禁止静默取第一条。 */
    private Map<String, JsonNode> index(JsonNode rows) {
        if (!rows.isArray()) {
            throw new IllegalArgumentException("逐工序证据不是数组");
        }
        Map<String, JsonNode> indexed = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String issue = text(row, "issueNo");
            String process = text(row, "processCode");
            if (StrUtil.isBlank(issue) || StrUtil.isBlank(process)
                    || indexed.putIfAbsent(issue + '\u0000' + process, row) != null) {
                throw new IllegalArgumentException("计划号/工序缺失或重复，无法唯一对照");
            }
        }
        return indexed;
    }

    /** 对照计划量不变且与本次采集目标一致；执行事实缺失/倒置/未来时刻不得推算收益。 */
    private String validate(JsonNode old, JsonNode after, JsonNode actual, JsonNode currentPlan,
                            LocalDateTime observed) {
        if (old == null || text(old, "issueId") == null || text(old, "operationSeq") == null
                || !Objects.equals(text(old, "issueId"), text(after, "issueId"))
                || !Objects.equals(text(after, "issueId"), text(actual, "issueId"))
                || !Objects.equals(text(old, "operationSeq"), text(after, "operationSeq"))
                || !Objects.equals(text(after, "operationSeq"), text(actual, "operationSeq"))) {
            return "旧、新、MES工序主键或序号不一致";
        }
        String oldQuantity = text(old, "plannedQuantity");
        String newQuantity = text(after, "plannedQuantity");
        String observedQuantity = text(currentPlan, "plannedQuantity");
        if (oldQuantity == null || newQuantity == null
                || observedQuantity == null || new BigDecimal(newQuantity).signum() <= 0
                || new BigDecimal(oldQuantity).compareTo(new BigDecimal(newQuantity)) != 0
                || new BigDecimal(newQuantity).compareTo(new BigDecimal(observedQuantity)) != 0) {
            return "计划数量缺失或变化，不能同口径比较";
        }
        if (!validPlan(old) || !validPlan(after)
                || !Objects.equals(time(after, "scheduledStartTime"), time(actual, "plannedStartTime"))
                || !Objects.equals(time(after, "scheduledEndTime"), time(actual, "plannedEndTime"))
                || !Objects.equals(text(after, "resourceCode"), text(actual, "resourceCode"))) {
            return "计划时刻/资源无效或本次采集目标与冻结候选不一致";
        }
        if (!actual.path("matched").asBoolean(false) || !actual.path("mesTaskPresent").asBoolean(false)
                || StrUtil.isBlank(text(actual, "mesTaskCode")) || StrUtil.isNotBlank(text(actual, "gapReason"))) {
            return "MES任务或工序映射缺失";
        }
        LocalDateTime start = time(actual, "actualStartTime");
        LocalDateTime finish = time(actual, "actualFinishTime");
        String completeQuantity = text(actual, "completeQuantity");
        if (start == null && completeQuantity != null && new BigDecimal(completeQuantity).signum() > 0) {
            return "已有实际产量但缺少实际开工时刻，不能按未开工统计";
        }
        if (start != null && start.isAfter(observed) || finish != null && (start == null
                || finish.isBefore(start) || finish.isAfter(observed))) {
            return "实际开完工时间不完整、倒置或晚于观察时间";
        }
        return null;
    }

    private boolean validPlan(JsonNode plan) {
        LocalDateTime start = time(plan, "scheduledStartTime");
        LocalDateTime end = time(plan, "scheduledEndTime");
        return start != null && end != null && end.isAfter(start);
    }

    private Operation row(JsonNode old, JsonNode after, JsonNode actual, String gap) {
        LocalDateTime oldStart = time(old, "scheduledStartTime");
        LocalDateTime oldEnd = time(old, "scheduledEndTime");
        LocalDateTime newStart = time(after, "scheduledStartTime");
        LocalDateTime newEnd = time(after, "scheduledEndTime");
        LocalDateTime start = time(actual, "actualStartTime");
        LocalDateTime finish = time(actual, "actualFinishTime");
        return new Operation(text(after, "issueNo"), text(after, "processCode"), text(after, "processName"),
                text(actual, "mesTaskCode"), gap == null ? "COMPARABLE" : "EXCLUDED", gap,
                oldStart, oldEnd, newStart, newEnd, start, finish,
                gap == null ? late(oldStart, start) : null, gap == null ? late(newStart, start) : null,
                gap == null ? late(oldEnd, finish) : null, gap == null ? late(newEnd, finish) : null,
                oldEnd == null || newEnd == null ? null : Duration.between(oldEnd, newEnd).toMinutes());
    }

    private JsonNode read(String json) throws Exception {
        if (StrUtil.isBlank(json)) {
            throw new IllegalArgumentException("冻结快照为空");
        }
        return objectMapper.readTree(json);
    }

    private boolean hasItems(JsonNode node) {
        return !node.isArray() || !node.isEmpty();
    }

    private String text(JsonNode row, String field) {
        return row == null || row.path(field).isNull() || row.path(field).isMissingNode()
                ? null : row.path(field).asText();
    }

    private LocalDateTime time(JsonNode row, String field) {
        return text(row, field) == null ? null : objectMapper.convertValue(row.path(field), LocalDateTime.class);
    }

    private static Long late(LocalDateTime planned, LocalDateTime actual) {
        return planned == null || actual == null ? null : Math.max(0, Duration.between(planned, actual).toMinutes());
    }

    /** 单次对照的局部累加器，不跨观察窗口，也不持有数据库或共享状态。 */
    private final class Aggregate {
        private int count;
        private int started;
        private int finished;
        private int startAdherent;
        private int finishAdherent;
        private int pending;
        private long startLate;
        private long finishLate;

        private void add(LocalDateTime plannedStart, LocalDateTime plannedEnd, JsonNode actual,
                         LocalDateTime observed, int tolerance) {
            count++;
            LocalDateTime start = time(actual, "actualStartTime");
            LocalDateTime finish = time(actual, "actualFinishTime");
            if (start != null) {
                started++;
                startLate += late(plannedStart, start);
                if (Math.abs(Duration.between(plannedStart, start).toMinutes()) <= tolerance) {
                    startAdherent++;
                }
            } else if (Duration.between(plannedStart, observed).toMinutes() > tolerance) {
                pending++;
            }
            if (finish != null) {
                finished++;
                finishLate += late(plannedEnd, finish);
                if (Math.abs(Duration.between(plannedEnd, finish).toMinutes()) <= tolerance) {
                    finishAdherent++;
                }
            }
        }

        private Metrics metrics() {
            return new Metrics(count, started, finished, startAdherent, finishAdherent, rate(startAdherent, started),
                    rate(finishAdherent, finished), started == 0 ? null : startLate,
                    finished == 0 ? null : finishLate, pending);
        }

        private BigDecimal rate(int numerator, int denominator) {
            return denominator == 0 ? null : BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
        }
    }
}
