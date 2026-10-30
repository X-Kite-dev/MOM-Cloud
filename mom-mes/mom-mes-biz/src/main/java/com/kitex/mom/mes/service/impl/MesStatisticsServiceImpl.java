package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.mes.dto.MesOrderVO;
import com.kitex.mom.mes.dto.MesTaskProcessVO;
import com.kitex.mom.mes.dto.ProcessDefectRateDTO;
import com.kitex.mom.mes.dto.WorkOrderStatisticsDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.enums.MesTaskStatusEnum;
import com.kitex.mom.mes.enums.TimeRangeEnum;
import com.kitex.mom.mes.service.MesProduceTaskService;
import com.kitex.mom.mes.service.MesStatisticsService;
import com.kitex.mom.mes.service.MesTaskService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 生产看板
 *
 * @author kitex
 * @since 2025-04-07
 */
@Service
@AllArgsConstructor
public class MesStatisticsServiceImpl implements MesStatisticsService {

    private final MesTaskService taskService;
    private final MesProduceTaskService produceTaskService;

    /**
     * 出货订单数据
     *
     * @return
     */
    @Override
    public List<MesOrderVO> getOrderList(String startTime, String endTime) {
        List<MesOrderVO> resultList = new ArrayList<>();

        return resultList;
    }

    /**
     * 获取工单数量统计数据（支持时间范围）
     *
     * @param timeRange 时间范围枚举
     * @param startTime 开始时间（自定义时间范围时使用）
     * @param endTime   结束时间（自定义时间范围时使用）
     * @return 工单数量统计数据列表
     */
    @Override
    public List<WorkOrderStatisticsDTO> getWorkOrderStatistics(TimeRangeEnum timeRange, LocalDateTime startTime,
                                                               LocalDateTime endTime) {
        if (timeRange == null || (timeRange == TimeRangeEnum.CUSTOM && (startTime == null || endTime == null))) {
            throw new IllegalArgumentException("工单统计须指定有效时间范围");
        }
        LocalDateTime queryStartTime = timeRange == TimeRangeEnum.CUSTOM ? startTime : timeRange.getStartTime();
        LocalDateTime queryEndTime = timeRange == TimeRangeEnum.CUSTOM ? endTime : timeRange.getEndTime();
        if (queryEndTime.isBefore(queryStartTime)) {
            throw new IllegalArgumentException("统计结束时间不能早于开始时间");
        }
        // 到期工单队列的当前执行状态；无计划结束时间不归入任何日期，不回退到建单日。
        List<MesTaskEntity> tasks = taskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                .ge(MesTaskEntity::getPlanEndDate, queryStartTime)
                .le(MesTaskEntity::getPlanEndDate, queryEndTime));
        Map<LocalDate, List<MesTaskEntity>> tasksByDate = tasks.stream()
                .collect(Collectors.groupingBy(task -> task.getPlanEndDate().toLocalDate()));
        LocalDateTime observedAt = LocalDateTime.now();
        List<WorkOrderStatisticsDTO> result = new ArrayList<>();
        for (LocalDate date = queryStartTime.toLocalDate(); !date.isAfter(queryEndTime.toLocalDate()); date = date
                .plusDays(1)) {
            List<MesTaskEntity> dayTasks = tasksByDate.getOrDefault(date, Collections.emptyList());
            WorkOrderStatisticsDTO dto = new WorkOrderStatisticsDTO();
            dto.setStatisticsDate(date);
            dto.setDateStr(date.format(DateTimeFormatter.ofPattern("MM-dd")));
            dto.setTotalCount((long) dayTasks.size());
            long completed = 0, inProgress = 0, notStarted = 0, closed = 0, unknown = 0;
            long onTime = 0, delayed = 0, missingFinish = 0, overdue = 0, eligible = 0;
            for (MesTaskEntity task : dayTasks) {
                String status = task.getStatus();
                boolean closedTask = MesTaskStatusEnum.CLOSE.name().equals(status);
                if (closedTask) {
                    closed++;
                }
                // CLOSE也用于未开工撤回和任意关闭；只有真实完工时间能证明关闭前曾完成。
                boolean finished = MesTaskStatusEnum.FINISH.name().equals(status)
                        || (closedTask && task.getFinishTime() != null);
                if (finished) {
                    completed++;
                    eligible++;
                    if (task.getFinishTime() == null) {
                        missingFinish++;
                    } else if (!task.getFinishTime().isAfter(task.getPlanEndDate())) {
                        onTime++;
                    } else {
                        delayed++;
                    }
                } else if (MesTaskStatusEnum.PRODUCT.name().equals(status)) {
                    inProgress++;
                    eligible++;
                    if (task.getPlanEndDate().isBefore(observedAt)) {
                        overdue++;
                    }
                } else if (Set.of(MesTaskStatusEnum.CREATE.name(), MesTaskStatusEnum.ISSUE.name(),
                        MesTaskStatusEnum.DISPATCH.name()).contains(Objects.toString(status, ""))) {
                    notStarted++;
                    eligible++;
                    if (task.getPlanEndDate().isBefore(observedAt)) {
                        overdue++;
                    }
                } else if (!closedTask) {
                    unknown++;
                }
            }
            dto.setCompletedCount(completed);
            dto.setInProgressCount(inProgress);
            dto.setNotStartedCount(notStarted);
            // 工单没有PAUSE/CANCEL状态，不能用派工状态或关闭状态冒充。
            dto.setPausedCount(0L);
            dto.setCancelledCount(0L);
            dto.setClosedCount(closed);
            dto.setUnknownStatusCount(unknown);
            dto.setEligibleCount(eligible);
            dto.setUnknownCompletionTimeCount(missingFinish);
            dto.setOverdueUnfinishedCount(overdue);
            dto.setOnTimeCompletedCount(onTime);
            dto.setDelayedCompletedCount(delayed);
            dto.setCompletionRate(eligible == 0 ? 0.0 : completed * 100.0 / eligible);
            dto.setOnTimeCompletionRate(eligible == 0 ? 0.0 : onTime * 100.0 / eligible);
            result.add(dto);
        }
        return result;
    }

    /**
     * 获取工序不良率排名数据（支持时间范围）
     *
     * @param timeRange 时间范围枚举
     * @param startTime 开始时间（自定义时间范围时使用）
     * @param endTime   结束时间（自定义时间范围时使用）
     * @param limit     返回数量限制
     * @return 工序不良率排名数据列表
     */
    @Override
    public List<ProcessDefectRateDTO> getProcessDefectRateRanking(TimeRangeEnum timeRange, LocalDateTime startTime,
                                                                  LocalDateTime endTime, Integer limit) {
        // 确定查询时间范围
        LocalDateTime queryStartTime;
        LocalDateTime queryEndTime;

        if (timeRange == TimeRangeEnum.CUSTOM && startTime != null && endTime != null) {
            queryStartTime = startTime;
            queryEndTime = endTime;
        } else {
            queryStartTime = timeRange.getStartTime();
            queryEndTime = timeRange.getEndTime();
        }

        // 转换为Date类型进行查询
        Date startDate = Date.from(queryStartTime.atZone(ZoneId.systemDefault()).toInstant());
        Date endDate = Date.from(queryEndTime.atZone(ZoneId.systemDefault()).toInstant());

        // 查询指定时间范围内的生产任务数据
        List<MesProduceTaskEntity> produceTasks = produceTaskService.list(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .ge(MesProduceTaskEntity::getCreateTime, startDate)
                .le(MesProduceTaskEntity::getCreateTime, endDate));

        // 当前工序累计GOOD/NG快照，按实际工序编码分组；不是检验事件期间或一次合格率。
        Map<String, List<MesProduceTaskEntity>> tasksByProcess = produceTasks.stream()
                .collect(Collectors.groupingBy(task -> Objects.toString(task.getRouteCode(), "")));
        List<ProcessDefectRateDTO> result = new ArrayList<>();
        for (List<MesProduceTaskEntity> group : tasksByProcess.values()) {
            MesProduceTaskEntity first = group.get(0);
            ProcessDefectRateDTO dto = new ProcessDefectRateDTO();
            dto.setProcessCode(first.getRouteCode());
            dto.setProcessName(first.getRouteName());
            dto.setProcessDescription(Objects.toString(first.getRouteName(), first.getRouteCode()));
            boolean known = group.stream().allMatch(task -> task.getCompleteNum() != null
                    && task.getUnPassNum() != null && task.getCompleteNum().signum() >= 0
                    && task.getUnPassNum().signum() >= 0);
            if (known) {
                BigDecimal good = group.stream().map(MesProduceTaskEntity::getCompleteNum).reduce(BigDecimal.ZERO,
                        BigDecimal::add);
                BigDecimal ng = group.stream().map(MesProduceTaskEntity::getUnPassNum).reduce(BigDecimal.ZERO,
                        BigDecimal::add);
                BigDecimal total = good.add(ng);
                dto.setTotalInspectionCount(total);
                dto.setQualifiedCount(good);
                dto.setDefectCount(ng);
                if (total.signum() > 0) {
                    BigDecimal rate = ng.multiply(BigDecimal.valueOf(100)).divide(total, 4, RoundingMode.HALF_UP);
                    dto.setDefectRate(rate);
                    dto.setQualifiedRate(BigDecimal.valueOf(100).subtract(rate));
                }
            }
            dto.setDefectRateStr(
                    dto.getDefectRate() == null ? "--" : dto.getDefectRate().setScale(2, RoundingMode.HALF_UP) + "%");
            dto.setQualifiedRateStr(dto.getQualifiedRate() == null ? "--"
                    : dto.getQualifiedRate().setScale(2, RoundingMode.HALF_UP) + "%");
            // 未查询真实原因及历史对比时保持未知，不制造“工艺参数偏差”或稳定趋势。
            dto.setTrendFlag("unknown");
            result.add(dto);
        }
        result.sort(Comparator.comparing(ProcessDefectRateDTO::getDefectRate,
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(
                ProcessDefectRateDTO::getProcessCode, Comparator.nullsLast(Comparator.naturalOrder())));
        for (int i = 0; i < result.size(); i++) {
            result.get(i).setRanking(i + 1);
        }
        if (limit != null && limit > 0 && result.size() > limit) {
            return result.subList(0, limit);
        }
        return result;
    }

    /**
     * 获取出货产品数（支持时间范围）
     */
    @Override
    public Map<String, Object> getProductNum(TimeRangeEnum timeRange, LocalDateTime startTime, LocalDateTime endTime) {
        // 确定查询时间范围
        LocalDateTime queryStartTime;
        LocalDateTime queryEndTime;

        if (timeRange == TimeRangeEnum.CUSTOM && startTime != null && endTime != null) {
            queryStartTime = startTime;
            queryEndTime = endTime;
        } else {
            queryStartTime = timeRange.getStartTime();
            queryEndTime = timeRange.getEndTime();
        }

        // 转换为Date类型进行查询
        Date startDate = Date.from(queryStartTime.atZone(ZoneId.systemDefault()).toInstant());
        Date endDate = Date.from(queryEndTime.atZone(ZoneId.systemDefault()).toInstant());

        // 查询指定时间范围内的已完成生产任务
        List<MesProduceTaskEntity> completedTasks = produceTaskService
                .list(new LambdaQueryWrapper<MesProduceTaskEntity>()
                        .ge(MesProduceTaskEntity::getCreateTime, startDate)
                        .le(MesProduceTaskEntity::getCreateTime, endDate));

        // 这是当前工序GOOD累计量，不是物流出货量；保留业务数量的小数与未知证据。
        boolean known = completedTasks.stream()
                .allMatch(task -> task.getCompleteNum() != null && task.getCompleteNum().signum() >= 0);
        BigDecimal currentSum = known ? completedTasks.stream().map(MesProduceTaskEntity::getCompleteNum)
                .reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        Map<String, Object> result = new HashMap<>();
        result.put("currentMonthSum", currentSum == null ? "--" : currentSum.stripTrailingZeros().toPlainString());
        result.put("momComparison", "--"); // 未计算历史对比
        result.put("momFlag", "unknown");
        result.put("yoyComparison", "--"); // 未计算历史对比
        result.put("yoyFlag", "unknown");

        return result;
    }

    /**
     * 获取生产工单数（支持时间范围）
     */
    @Override
    public Map<String, Object> getTaskNum(TimeRangeEnum timeRange, LocalDateTime startTime, LocalDateTime endTime) {
        // 确定查询时间范围
        LocalDateTime queryStartTime;
        LocalDateTime queryEndTime;

        if (timeRange == TimeRangeEnum.CUSTOM && startTime != null && endTime != null) {
            queryStartTime = startTime;
            queryEndTime = endTime;
        } else {
            queryStartTime = timeRange.getStartTime();
            queryEndTime = timeRange.getEndTime();
        }

        // 转换为Date类型进行查询
        Date startDate = Date.from(queryStartTime.atZone(ZoneId.systemDefault()).toInstant());
        Date endDate = Date.from(queryEndTime.atZone(ZoneId.systemDefault()).toInstant());

        // 查询指定时间范围内的工单
        List<MesTaskEntity> tasks = taskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                .ge(MesTaskEntity::getCreateTime, startDate)
                .le(MesTaskEntity::getCreateTime, endDate));

        long currentSum = tasks.size();

        Map<String, Object> result = new HashMap<>();
        result.put("currentMonthSum", String.valueOf(currentSum));
        result.put("momComparison", "--"); // 未计算历史对比
        result.put("momFlag", "unknown");
        result.put("yoyComparison", "--"); // 未计算历史对比
        result.put("yoyFlag", "unknown");

        return result;
    }

    /**
     * 获取在制品数（支持时间范围）
     */
    @Override
    public Map<String, Object> getWipNum(TimeRangeEnum timeRange, LocalDateTime startTime, LocalDateTime endTime) {
        // 确定查询时间范围
        LocalDateTime queryStartTime;
        LocalDateTime queryEndTime;

        if (timeRange == TimeRangeEnum.CUSTOM && startTime != null && endTime != null) {
            queryStartTime = startTime;
            queryEndTime = endTime;
        } else {
            queryStartTime = timeRange.getStartTime();
            queryEndTime = timeRange.getEndTime();
        }

        // 转换为Date类型进行查询
        Date startDate = Date.from(queryStartTime.atZone(ZoneId.systemDefault()).toInstant());
        Date endDate = Date.from(queryEndTime.atZone(ZoneId.systemDefault()).toInstant());

        // 查询指定时间范围内的在制品（未完成的生产任务）
        List<MesProduceTaskEntity> wipTasks = produceTaskService.list(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .ge(MesProduceTaskEntity::getCreateTime, startDate)
                .le(MesProduceTaskEntity::getCreateTime, endDate)
                .eq(MesProduceTaskEntity::getStatus, MesProduceTaskStatusEnum.PRODUCT.name()));

        long currentSum = wipTasks.size();

        Map<String, Object> result = new HashMap<>();
        result.put("currentMonthSum", String.valueOf(currentSum));
        result.put("momComparison", "--"); // 未计算历史对比
        result.put("momFlag", "unknown");
        result.put("yoyComparison", "--"); // 未计算历史对比
        result.put("yoyFlag", "unknown");

        return result;
    }

    /**
     * 获取不良品数（支持时间范围）
     */
    @Override
    public Map<String, Object> getBadNum(TimeRangeEnum timeRange, LocalDateTime startTime, LocalDateTime endTime) {
        // 确定查询时间范围
        LocalDateTime queryStartTime;
        LocalDateTime queryEndTime;

        if (timeRange == TimeRangeEnum.CUSTOM && startTime != null && endTime != null) {
            queryStartTime = startTime;
            queryEndTime = endTime;
        } else {
            queryStartTime = timeRange.getStartTime();
            queryEndTime = timeRange.getEndTime();
        }

        // 转换为Date类型进行查询
        Date startDate = Date.from(queryStartTime.atZone(ZoneId.systemDefault()).toInstant());
        Date endDate = Date.from(queryEndTime.atZone(ZoneId.systemDefault()).toInstant());

        // 查询指定时间范围内的生产任务，计算不良品数
        List<MesProduceTaskEntity> produceTasks = produceTaskService.list(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .ge(MesProduceTaskEntity::getCreateTime, startDate)
                .le(MesProduceTaskEntity::getCreateTime, endDate));

        // 累计NG快照保留小数；历史脏值或未知值不能当作零。
        boolean known = produceTasks.stream()
                .allMatch(task -> task.getUnPassNum() != null && task.getUnPassNum().signum() >= 0);
        BigDecimal currentSum = known ? produceTasks.stream().map(MesProduceTaskEntity::getUnPassNum)
                .reduce(BigDecimal.ZERO, BigDecimal::add) : null;
        Map<String, Object> result = new HashMap<>();
        result.put("currentMonthSum", currentSum == null ? "--" : currentSum.stripTrailingZeros().toPlainString());
        result.put("momComparison", "--");
        result.put("momFlag", "unknown");
        result.put("yoyComparison", "--");
        result.put("yoyFlag", "unknown");

        return result;
    }

    /**
     * 获取工单实时进度列表（支持时间范围）
     */
    @Override
    public List<MesTaskProcessVO> getTaskProgressList(TimeRangeEnum timeRange, LocalDateTime startTime,
                                                      LocalDateTime endTime, Integer limit) {
        // 确定查询时间范围
        LocalDateTime queryStartTime;
        LocalDateTime queryEndTime;

        if (timeRange == TimeRangeEnum.CUSTOM && startTime != null && endTime != null) {
            queryStartTime = startTime;
            queryEndTime = endTime;
        } else {
            queryStartTime = timeRange.getStartTime();
            queryEndTime = timeRange.getEndTime();
        }

        // 转换为Date类型进行查询
        Date startDate = Date.from(queryStartTime.atZone(ZoneId.systemDefault()).toInstant());
        Date endDate = Date.from(queryEndTime.atZone(ZoneId.systemDefault()).toInstant());

        // 查询指定时间范围内的工单
        List<MesTaskEntity> tasks = taskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                .ge(MesTaskEntity::getCreateTime, startDate)
                .le(MesTaskEntity::getCreateTime, endDate)
                .orderByDesc(MesTaskEntity::getCreateTime)
                .last("LIMIT " + (limit != null ? limit : 10)));

        List<MesTaskProcessVO> result = new ArrayList<>();

        for (MesTaskEntity task : tasks) {
            MesTaskProcessVO processVO = new MesTaskProcessVO();
            processVO.setTaskCode(task.getCode());
            // 计算进度
            BigDecimal totalNum = task.getNoticeNum() != null ? task.getNoticeNum() : BigDecimal.ZERO;
            BigDecimal completedNum = task.getCompleteNum() != null ? task.getCompleteNum() : BigDecimal.ZERO;

            // 计算进度百分比
            BigDecimal progress = BigDecimal.ZERO;
            if (totalNum.compareTo(BigDecimal.ZERO) > 0) {
                progress = completedNum.divide(totalNum, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
                // 确保进度不超过100%
                if (progress.compareTo(new BigDecimal("100")) > 0) {
                    progress = new BigDecimal("100");
                }
            }

            processVO.setProgress(progress);
            processVO.setAllNum(totalNum);

            // 根据进度设置状态
            if (progress.compareTo(new BigDecimal("100")) == 0) {
                processVO.setStatus("已完工");
            } else if (progress.compareTo(BigDecimal.ZERO) > 0) {
                processVO.setStatus("在制");
            } else {
                processVO.setStatus("未开始");
            }

            result.add(processVO);
        }

        return result;
    }
}
