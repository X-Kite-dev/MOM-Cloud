package com.kitex.mom.mes.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.agent.dto.MesProductionProgressDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.enums.MesTaskStatusEnum;
import com.kitex.mom.mes.service.MesProduceTaskService;
import com.kitex.mom.mes.service.MesTaskService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 面向智能体的 MES 生产进度语义查询。
 *
 * <p>本服务直接使用生产工单和工序任务事实，不复用包含模拟同比/环比口径的看板接口。
 * 查询严格限定当前人员有权访问的实际工厂，并返回指标定义、观察时间、证据引用和数据缺口。</p>
 */
@Service
@RequiredArgsConstructor
public class MesProductionProgressQueryService {

    public static final String TOOL_CODE = "MES_PRODUCTION_PROGRESS_QUERY@1";
    private static final int MAX_RANGE_DAYS = 31;

    private final MesActualFactoryAccessService factoryAccess;
    private final MesTaskService taskService;
    private final MesProduceTaskService produceTaskService;

    public MesProductionProgressDTO.Evidence query(MesProductionProgressDTO.Query query) {
        String actualFactory = factoryAccess.require("read");
        if (query == null || !actualFactory.equals(query.getFactoryCode())) {
            throw new CheckedException("生产进度查询工厂与当前实际工厂不一致");
        }
        QueryRange range = normalize(query);
        long matchedCount = taskService.count(buildTaskQuery(query, range, false));
        List<MesTaskEntity> tasks = taskService.list(buildTaskQuery(query, range, true));
        List<String> taskCodes = tasks.stream().map(MesTaskEntity::getCode)
                .filter(StringUtils::hasText).distinct().toList();
        Map<String, List<MesProduceTaskEntity>> operationMap = taskCodes.isEmpty()
                ? Map.of()
                : produceTaskService.lambdaQuery()
                .eq(MesProduceTaskEntity::getFactoryCode, actualFactory)
                .in(MesProduceTaskEntity::getTaskCode, taskCodes)
                .orderByAsc(MesProduceTaskEntity::getTaskCode)
                .orderByAsc(MesProduceTaskEntity::getRouteOrder)
                .orderByAsc(MesProduceTaskEntity::getId)
                .list().stream().collect(Collectors.groupingBy(MesProduceTaskEntity::getTaskCode));

        LocalDateTime observedAt = LocalDateTime.now();
        List<MesProductionProgressDTO.TaskProgress> rows = tasks.stream()
                .map(task -> toTaskProgress(task, operationMap.getOrDefault(task.getCode(), List.of()), observedAt))
                .toList();
        MesProductionProgressDTO.Summary summary = summarize(query, range, rows, matchedCount, observedAt);
        List<String> gaps = aggregateGaps(rows, matchedCount);
        List<String> refs = rows.stream().map(row -> "MES:mes_task:" + row.getTaskCode()).toList();

        return new MesProductionProgressDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setQueryScope(range.allTime() ? "ALL_TIME" : "TIME_RANGE")
                .setMetricDefinition(range.allTime()
                        ? "统计当前工厂权限范围内全部未删除生产工单，不附加时间条件；工单总数及未开始/生产中/已完成/延期状态数针对全部命中工单，数量合计、完成率和暂停工单数只针对limit返回明细。"
                        : "统计计划时间窗或实际执行时间窗与查询区间有交集的生产工单；工单总数及未开始/生产中/已完成/延期状态数针对全部命中工单。目标数量优先取工单需求数量，缺失时取已排产数量；数量合计、完成率和暂停工单数只针对limit返回明细。延期指未完成且计划结束早于观察时间，或实际完成晚于计划结束。")
                .setFactoryCode(actualFactory)
                .setRangeStart(range.start())
                .setRangeEnd(range.end())
                .setObservedAt(observedAt)
                .setSummary(summary)
                .setTasks(rows)
                .setDataGaps(gaps)
                .setEvidenceRefs(refs)
                .setTruncated(matchedCount > rows.size());
    }

    private QueryRange normalize(MesProductionProgressDTO.Query query) {
        int limit = query.getLimit() == null ? 20 : query.getLimit();
        query.setLimit(Math.max(1, Math.min(limit, 50)));
        if (Boolean.TRUE.equals(query.getAllTime())) {
            return new QueryRange(null, null, true);
        }
        LocalDate today = LocalDate.now();
        LocalDateTime start = query.getStartTime() == null ? today.atStartOfDay() : query.getStartTime();
        LocalDateTime end = query.getEndTime() == null ? today.plusDays(1).atStartOfDay() : query.getEndTime();
        if (!end.isAfter(start)) {
            throw new CheckedException("生产进度查询结束时间必须晚于开始时间");
        }
        if (end.isAfter(start.plusDays(MAX_RANGE_DAYS))) {
            throw new CheckedException("生产进度单次查询区间不能超过31天");
        }
        return new QueryRange(start, end, false);
    }

    private LambdaQueryWrapper<MesTaskEntity> buildTaskQuery(MesProductionProgressDTO.Query query,
                                                             QueryRange range, boolean limited) {
        LambdaQueryWrapper<MesTaskEntity> wrapper = new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getFactoryCode, query.getFactoryCode())
                .eq(StringUtils.hasText(query.getTaskCode()), MesTaskEntity::getCode, query.getTaskCode())
                .eq(StringUtils.hasText(query.getWorkshopCode()), MesTaskEntity::getWorkshopCode, query.getWorkshopCode());
        if (!range.allTime() && !StringUtils.hasText(query.getTaskCode())) {
            wrapper.and(scope -> scope
                    .and(plan -> plan.le(MesTaskEntity::getPlanStartDate, range.end())
                            .ge(MesTaskEntity::getPlanEndDate, range.start()))
                    .or(actual -> actual.le(MesTaskEntity::getStartTime, range.end())
                            .and(finish -> finish.isNull(MesTaskEntity::getFinishTime)
                                    .or().ge(MesTaskEntity::getFinishTime, range.start())))
                    .or(activeWithoutTime -> activeWithoutTime
                            .isNull(MesTaskEntity::getPlanStartDate)
                            .isNull(MesTaskEntity::getPlanEndDate)
                            .in(MesTaskEntity::getStatus, MesTaskStatusEnum.DISPATCH.name(), MesTaskStatusEnum.PRODUCT.name())));
        }
        if (limited) {
            wrapper.orderByAsc(MesTaskEntity::getPlanEndDate)
                    .orderByAsc(MesTaskEntity::getCode)
                    .last("LIMIT " + query.getLimit());
        }
        return wrapper;
    }

    private MesProductionProgressDTO.TaskProgress toTaskProgress(MesTaskEntity task,
                                                                 List<MesProduceTaskEntity> operations,
                                                                 LocalDateTime observedAt) {
        BigDecimal target = positiveOrFallback(task.getNoticeNum(), task.getPlannedNum());
        BigDecimal completed = zero(task.getCompleteNum());
        BigDecimal unqualified = task.getUnPassNum();
        List<String> gaps = new ArrayList<>();
        if (task.getPlanStartDate() == null || task.getPlanEndDate() == null) {
            gaps.add("工单缺少完整计划起止时间");
        }
        if (target.signum() <= 0) {
            gaps.add("工单缺少有效目标数量");
        }
        if (operations.isEmpty()) {
            gaps.add("工单尚无工序任务");
        }
        if (unqualified == null || unqualified.signum() < 0) {
            unqualified = null;
            gaps.add("不合格数量未维护或非法，不可按零判定质量正常");
        }
        boolean knownStatus = java.util.Arrays.stream(MesTaskStatusEnum.values())
                .anyMatch(status -> status.name().equals(task.getStatus()));
        boolean missingFinish = knownStatus && task.getFinishTime() == null
                && List.of("FINISH", "CLOSE").contains(task.getStatus());
        if (!knownStatus || missingFinish) {
            gaps.add("工单状态未识别或完成/关闭缺实际完工时间，不能确定逾期");
        }
        if (operations.stream().anyMatch(operation -> java.util.Arrays.stream(MesProduceTaskStatusEnum.values())
                .noneMatch(status -> status.name().equals(operation.getStatus())))) {
            gaps.add("存在未识别工序状态，已知暂停数不代表所有工序正常");
        }
        if (target.signum() > 0 && completed.compareTo(target) > 0) {
            gaps.add("完成数量超过目标数量，需核对返工或累计口径");
        }
        Boolean delayed = !knownStatus || missingFinish || task.getPlanEndDate() == null ? null : (
                (task.getFinishTime() == null && !MesTaskStatusEnum.FINISH.name().equals(task.getStatus())
                        && task.getPlanEndDate().isBefore(observedAt))
                        || (task.getFinishTime() != null && task.getFinishTime().isAfter(task.getPlanEndDate())));
        Predicate<MesProduceTaskEntity> finished = item -> MesProduceTaskStatusEnum.FINISH.name().equals(item.getStatus());
        Predicate<MesProduceTaskEntity> active = item -> MesProduceTaskStatusEnum.PRODUCT.name().equals(item.getStatus());
        Predicate<MesProduceTaskEntity> paused = item -> MesProduceTaskStatusEnum.PAUSE.name().equals(item.getStatus());
        List<MesProductionProgressDTO.OperationProgress> currentOperations = operations.stream()
                .filter(item -> active.test(item) || paused.test(item)
                        || MesProduceTaskStatusEnum.RECEIVE.name().equals(item.getStatus()))
                .sorted(Comparator.comparing(MesProduceTaskEntity::getRouteOrder,
                        Comparator.nullsLast(Integer::compareTo)))
                .limit(10)
                .map(this::toOperationProgress)
                .toList();

        return new MesProductionProgressDTO.TaskProgress()
                .setTaskId(task.getId())
                .setTaskCode(task.getCode())
                .setProductionOrder(task.getProductionOrder())
                .setPlanNo(task.getPlanNo())
                .setMaterialCode(task.getMatCode())
                .setMaterialName(task.getMatName())
                .setWorkshopCode(task.getWorkshopCode())
                .setWorkshopName(task.getWorkshopName())
                .setStatus(task.getStatus())
                .setPlannedStartTime(task.getPlanStartDate())
                .setPlannedEndTime(task.getPlanEndDate())
                .setActualStartTime(task.getStartTime())
                .setActualFinishTime(task.getFinishTime())
                .setTargetQuantity(target)
                .setCompletedQuantity(completed)
                .setUnqualifiedQuantity(unqualified)
                .setCompletionRate(rate(completed, target))
                .setDelayed(delayed)
                .setOperationCount(operations.size())
                .setFinishedOperationCount((int) operations.stream().filter(finished).count())
                .setActiveOperationCount((int) operations.stream().filter(active).count())
                .setPausedOperationCount(operations.isEmpty() ? null : (int) operations.stream().filter(paused).count())
                .setCurrentOperations(currentOperations)
                .setGaps(gaps);
    }

    private MesProductionProgressDTO.OperationProgress toOperationProgress(MesProduceTaskEntity operation) {
        return new MesProductionProgressDTO.OperationProgress()
                .setOperationTaskCode(operation.getCode())
                .setRouteCode(operation.getRouteCode())
                .setRouteName(operation.getRouteName())
                .setRouteOrder(operation.getRouteOrder())
                .setStatus(operation.getStatus())
                .setWorksiteCode(operation.getPlannedWorksiteCode())
                .setDeviceCode(operation.getPlannedDeviceCode())
                .setPlannedStartTime(operation.getPlannedStartTime())
                .setPlannedEndTime(operation.getPlannedEndTime())
                .setActualStartTime(operation.getStartTime())
                .setActualFinishTime(operation.getFinishTime())
                .setTargetQuantity(positiveOrFallback(operation.getNoticeNum(), operation.getPlannedNum()))
                .setCompletedQuantity(zero(operation.getCompleteNum()))
                .setUnqualifiedQuantity(zero(operation.getUnPassNum()));
    }

    private MesProductionProgressDTO.Summary summarize(MesProductionProgressDTO.Query query,
                                                       QueryRange range,
                                                       List<MesProductionProgressDTO.TaskProgress> rows,
                                                       long matchedCount,
                                                       LocalDateTime observedAt) {
        BigDecimal target = rows.stream().map(MesProductionProgressDTO.TaskProgress::getTargetQuantity)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal completed = rows.stream().map(MesProductionProgressDTO.TaskProgress::getCompletedQuantity)
                .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        // 任一明细缺数量时，不能把其余已知数之和包装成全部返回明细的完整合计。
        BigDecimal unqualified = rows.stream().anyMatch(row -> row.getUnqualifiedQuantity() == null)
                ? null : rows.stream().map(MesProductionProgressDTO.TaskProgress::getUnqualifiedQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new MesProductionProgressDTO.Summary()
                .setTaskStatusMetricScope("MATCHED")
                .setQuantityMetricScope("RETURNED_DETAILS")
                .setPausedMetricScope("RETURNED_DETAILS")
                .setMatchedTaskCount(matchedCount)
                .setReturnedTaskCount((long) rows.size())
                .setNotStartedTaskCount(countByStatuses(query, range, List.of(
                        MesTaskStatusEnum.CREATE.name(), MesTaskStatusEnum.ISSUE.name(),
                        MesTaskStatusEnum.DISPATCH.name())))
                .setInProgressTaskCount(countByStatuses(query, range, List.of(MesTaskStatusEnum.PRODUCT.name())))
                .setPausedTaskCount(rows.stream().filter(row -> row.getPausedOperationCount() != null && row.getPausedOperationCount() > 0).count())
                .setFinishedTaskCount(countByStatuses(query, range, List.of(MesTaskStatusEnum.FINISH.name())))
                .setDelayedTaskCount(countDelayed(query, range, observedAt))
                .setTargetQuantity(target)
                .setCompletedQuantity(completed)
                .setUnqualifiedQuantity(unqualified)
                .setCompletionRate(rate(completed, target));
    }

    /**
     * 状态数量对全部命中工单独立计数，不能受用于展示的明细上限影响。
     */
    private long countByStatuses(MesProductionProgressDTO.Query query, QueryRange range, List<String> statuses) {
        return taskService.count(buildTaskQuery(query, range, false).in(MesTaskEntity::getStatus, statuses));
    }

    /**
     * 延期数量沿用明细的同一口径，但在数据库侧统计全部命中工单。
     */
    private long countDelayed(MesProductionProgressDTO.Query query, QueryRange range, LocalDateTime observedAt) {
        return taskService.count(buildTaskQuery(query, range, false)
                .and(delayed -> delayed
                        .and(open -> open.isNull(MesTaskEntity::getFinishTime)
                                .ne(MesTaskEntity::getStatus, MesTaskStatusEnum.FINISH.name())
                                .lt(MesTaskEntity::getPlanEndDate, observedAt))
                        .or(finishedLate -> finishedLate.apply("finish_time > plan_end_date"))));
    }

    private List<String> aggregateGaps(List<MesProductionProgressDTO.TaskProgress> rows, long matchedCount) {
        List<String> gaps = new ArrayList<>();
        long missingSchedule = rows.stream().filter(row -> row.getPlannedStartTime() == null || row.getPlannedEndTime() == null).count();
        long missingTarget = rows.stream().filter(row -> row.getTargetQuantity() == null || row.getTargetQuantity().signum() <= 0).count();
        long missingOperations = rows.stream().filter(row -> row.getOperationCount() == null || row.getOperationCount() == 0).count();
        if (missingSchedule > 0) gaps.add(missingSchedule + "个返回工单缺少完整计划时间");
        if (missingTarget > 0) gaps.add(missingTarget + "个返回工单缺少有效目标数量");
        if (missingOperations > 0) gaps.add(missingOperations + "个返回工单尚无工序任务");
        if (matchedCount > rows.size())
            gaps.add("命中" + matchedCount + "个工单，本次按上限返回" + rows.size() + "个；汇总数量仅针对返回明细，不能代表全部命中工单");
        return gaps;
    }

    private BigDecimal positiveOrFallback(BigDecimal primary, BigDecimal fallback) {
        if (primary != null && primary.signum() > 0) return primary;
        return zero(fallback);
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal rate(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() <= 0) return BigDecimal.ZERO;
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private record QueryRange(LocalDateTime start, LocalDateTime end, boolean allTime) {
    }
}
