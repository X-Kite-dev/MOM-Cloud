package com.kitex.mom.mes.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.agent.dto.MesOperationWipDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.service.MesProduceTaskService;
import com.kitex.mom.mes.service.impl.MesActualFactoryAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 面向智能体的工序在制和停滞风险语义查询。
 *
 * <p>风险仅依据暂停状态、计划逾期和到达计划开工时间仍未开工三类可核验事实判定。
 * 没有节拍、队列或设备负荷证据时不会把某道工序武断地描述为产线瓶颈。</p>
 */
@Service
@RequiredArgsConstructor
public class MesOperationWipQueryService {

    public static final String TOOL_CODE = "MES_OPERATION_WIP_QUERY@1";
    private static final List<String> OPEN_STATUSES = List.of(
            MesProduceTaskStatusEnum.WAIT.name(), MesProduceTaskStatusEnum.UN_RECEIVE.name(),
            MesProduceTaskStatusEnum.RECEIVE.name(), MesProduceTaskStatusEnum.PRODUCT.name(),
            MesProduceTaskStatusEnum.PAUSE.name());

    private final MesActualFactoryAccessService factoryAccess;
    private final MesProduceTaskService produceTaskService;

    public MesOperationWipDTO.Evidence query(MesOperationWipDTO.Query query) {
        String actualFactory = factoryAccess.require("read");
        if (query == null || !actualFactory.equals(query.getFactoryCode())) {
            throw new CheckedException("工序在制查询工厂与当前实际工厂不一致");
        }
        int limit = query.getLimit() == null ? 20 : Math.max(1, Math.min(query.getLimit(), 50));
        query.setLimit(limit);
        LocalDateTime observedAt = LocalDateTime.now();

        long openCount = produceTaskService.count(open(query));
        long producingCount = produceTaskService.count(open(query)
                .eq("status", MesProduceTaskStatusEnum.PRODUCT.name()));
        long pausedCount = produceTaskService.count(open(query)
                .eq("status", MesProduceTaskStatusEnum.PAUSE.name()));
        long overdueCount = produceTaskService.count(open(query)
                .lt("planned_end_time", observedAt));
        long waitingCount = produceTaskService.count(open(query)
                .isNull("start_time").lt("planned_start_time", observedAt)
                .in("status", MesProduceTaskStatusEnum.WAIT.name(),
                        MesProduceTaskStatusEnum.UN_RECEIVE.name(), MesProduceTaskStatusEnum.RECEIVE.name()));
        long attentionCount = produceTaskService.count(attention(query, observedAt));

        List<Map<String, Object>> statusRows = produceTaskService.getBaseMapper().selectMaps(
                open(query).select("status", "COUNT(*) AS operation_count").groupBy("status").orderByAsc("status"));
        List<MesOperationWipDTO.StatusSummary> statuses = statusRows.stream()
                .map(row -> new MesOperationWipDTO.StatusSummary()
                        .setStatus(text(row.get("status")))
                        .setStatusName(statusName(text(row.get("status"))))
                        .setOperationCount(number(row.get("operation_count")).longValue()))
                .toList();

        List<MesProduceTaskEntity> entities = produceTaskService.list(open(query)
                .last("ORDER BY CASE WHEN status = 'PAUSE' THEN 0 "
                        + "WHEN planned_end_time < CURRENT_TIMESTAMP THEN 1 "
                        + "WHEN start_time IS NULL AND planned_start_time < CURRENT_TIMESTAMP THEN 2 ELSE 3 END, "
                        + "planned_end_time NULLS LAST, planned_start_time NULLS LAST, id LIMIT " + limit));
        List<MesOperationWipDTO.OperationAttention> operations = entities.stream()
                .map(item -> toOperation(item, observedAt)).toList();
        List<String> gaps = dataGaps(operations);
        if (attentionCount == 0 && openCount > 0) {
            gaps.add("当前未发现暂停、计划逾期或到期未开工工序；返回明细为最接近计划结束时间的在制工序");
        }
        if (openCount > operations.size()) {
            gaps.add("当前有" + openCount + "个未关闭工序，本次按风险优先返回" + operations.size() + "个");
        }
        List<String> refs = operations.stream()
                .map(item -> "MES:mes_produce_task:" + item.getOperationTaskCode()).toList();

        return new MesOperationWipDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("监控范围为WAIT、UN_RECEIVE、RECEIVE、PRODUCT、PAUSE状态的未关闭工序，其中PRODUCT是实际生产中工序。关注工序为暂停、计划结束已过但未关闭，或已到计划开工时间仍未实际开工；风险排序不是约束瓶颈结论。")
                .setFactoryCode(actualFactory)
                .setObservedAt(observedAt)
                .setSummary(new MesOperationWipDTO.Summary()
                        .setOpenOperationCount(openCount)
                        .setProducingOperationCount(producingCount)
                        .setPausedOperationCount(pausedCount)
                        .setOverdueOperationCount(overdueCount)
                        .setWaitingStartOperationCount(waitingCount)
                        .setAttentionOperationCount(attentionCount))
                .setStatusSummaries(statuses)
                .setOperations(operations)
                .setDataGaps(gaps)
                .setEvidenceRefs(refs)
                .setTruncated(openCount > operations.size());
    }

    private QueryWrapper<MesProduceTaskEntity> open(MesOperationWipDTO.Query query) {
        return new QueryWrapper<MesProduceTaskEntity>()
                .eq("factory_code", query.getFactoryCode())
                .in("status", OPEN_STATUSES)
                .eq(StringUtils.hasText(query.getTaskCode()), "task_code", query.getTaskCode())
                .eq(StringUtils.hasText(query.getWorkshopCode()), "workshop_code", query.getWorkshopCode());
    }

    private QueryWrapper<MesProduceTaskEntity> attention(MesOperationWipDTO.Query query, LocalDateTime observedAt) {
        return open(query).and(risk -> risk.eq("status", MesProduceTaskStatusEnum.PAUSE.name())
                .or().lt("planned_end_time", observedAt)
                .or(waiting -> waiting.isNull("start_time").lt("planned_start_time", observedAt)
                        .in("status", MesProduceTaskStatusEnum.WAIT.name(),
                                MesProduceTaskStatusEnum.UN_RECEIVE.name(),
                                MesProduceTaskStatusEnum.RECEIVE.name())));
    }

    private MesOperationWipDTO.OperationAttention toOperation(MesProduceTaskEntity item,
                                                              LocalDateTime observedAt) {
        boolean paused = MesProduceTaskStatusEnum.PAUSE.name().equals(item.getStatus());
        boolean overdue = item.getPlannedEndTime() != null && item.getPlannedEndTime().isBefore(observedAt);
        boolean waiting = item.getStartTime() == null && item.getPlannedStartTime() != null
                && item.getPlannedStartTime().isBefore(observedAt)
                && List.of(MesProduceTaskStatusEnum.WAIT.name(), MesProduceTaskStatusEnum.UN_RECEIVE.name(),
                        MesProduceTaskStatusEnum.RECEIVE.name())
                .contains(item.getStatus());
        List<String> reasons = new ArrayList<>();
        if (paused) reasons.add("工序处于暂停状态");
        if (overdue) reasons.add("已超过计划结束时间仍未关闭");
        if (waiting) reasons.add("已到计划开工时间但尚无实际开工时间");
        BigDecimal target = positiveOrFallback(item.getNoticeNum(), item.getPlannedNum());
        BigDecimal completed = zero(item.getCompleteNum());
        return new MesOperationWipDTO.OperationAttention()
                .setOperationId(item.getId())
                .setOperationTaskCode(item.getCode())
                .setTaskCode(item.getTaskCode())
                .setMaterialCode(item.getMatCode())
                .setMaterialName(item.getMatName())
                .setRouteCode(item.getRouteCode())
                .setRouteName(item.getRouteName())
                .setRouteOrder(item.getRouteOrder())
                .setStatus(item.getStatus())
                .setStatusName(statusName(item.getStatus()))
                .setWorkshopCode(item.getWorkshopCode())
                .setWorkshopName(item.getWorkshopName())
                .setWorksiteCode(item.getPlannedWorksiteCode())
                .setWorksiteName(item.getPlannedWorksiteName())
                .setDeviceCode(item.getPlannedDeviceCode())
                .setDeviceName(item.getPlannedDeviceName())
                .setPlannedStartTime(item.getPlannedStartTime())
                .setPlannedEndTime(item.getPlannedEndTime())
                .setActualStartTime(item.getStartTime())
                .setTargetQuantity(target)
                .setCompletedQuantity(completed)
                .setUnqualifiedQuantity(zero(item.getUnPassNum()))
                .setCompletionRate(rate(completed, target))
                .setOverdue(overdue)
                .setOverdueMinutes(overdue ? Duration.between(item.getPlannedEndTime(), observedAt).toMinutes() : 0L)
                .setWaitingStart(waiting)
                .setWaitingMinutes(waiting ? Duration.between(item.getPlannedStartTime(), observedAt).toMinutes() : 0L)
                .setAttentionLevel(paused ? "CRITICAL" : overdue ? "HIGH" : waiting ? "MEDIUM" : "NORMAL")
                .setRiskReasons(reasons);
    }

    private List<String> dataGaps(List<MesOperationWipDTO.OperationAttention> operations) {
        List<String> gaps = new ArrayList<>();
        long missingSchedule = operations.stream()
                .filter(item -> item.getPlannedStartTime() == null || item.getPlannedEndTime() == null).count();
        long missingTarget = operations.stream()
                .filter(item -> item.getTargetQuantity() == null || item.getTargetQuantity().signum() <= 0).count();
        if (missingSchedule > 0) gaps.add(missingSchedule + "个返回工序缺少完整计划时间，无法完整判断等待或逾期");
        if (missingTarget > 0) gaps.add(missingTarget + "个返回工序缺少有效目标数量");
        gaps.add("当前证据不包含标准节拍、实时队列和设备负荷，因此只给出停滞风险，不直接认定约束瓶颈");
        return gaps;
    }

    private String statusName(String status) {
        try {
            return MesProduceTaskStatusEnum.valueOf(status).getName();
        } catch (Exception ignored) {
            return "未识别状态";
        }
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

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private Number number(Object value) {
        return value instanceof Number number ? number : 0L;
    }
}
