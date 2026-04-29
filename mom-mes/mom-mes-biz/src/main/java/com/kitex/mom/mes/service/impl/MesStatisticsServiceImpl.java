package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.mes.dto.MesOrderVO;
import com.kitex.mom.mes.dto.MesTaskProcessVO;
import com.kitex.mom.mes.dto.ProcessDefectRateDTO;
import com.kitex.mom.mes.dto.WorkOrderStatisticsDTO;
import com.kitex.mom.mes.entity.MesProduceTaskEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
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
    public List<WorkOrderStatisticsDTO> getWorkOrderStatistics(TimeRangeEnum timeRange, LocalDateTime startTime, LocalDateTime endTime) {
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

        // 查询指定时间范围内的工单数据
        List<MesTaskEntity> tasks = taskService.list(new LambdaQueryWrapper<MesTaskEntity>()
                .ge(MesTaskEntity::getCreateTime, startDate)
                .le(MesTaskEntity::getCreateTime, endDate));

        // 按日期分组统计
        Map<LocalDate, List<MesTaskEntity>> tasksByDate = tasks.stream()
                .collect(Collectors.groupingBy(task ->
                        task.getCreateTime().toLocalDate()));

        // 生成统计数据
        List<WorkOrderStatisticsDTO> result = new ArrayList<>();
        LocalDate currentDate = queryStartTime.toLocalDate();
        LocalDate endLocalDate = queryEndTime.toLocalDate();

        while (!currentDate.isAfter(endLocalDate)) {
            List<MesTaskEntity> dayTasks = tasksByDate.getOrDefault(currentDate, new ArrayList<>());

            WorkOrderStatisticsDTO dto = new WorkOrderStatisticsDTO();
            dto.setStatisticsDate(currentDate);
            dto.setDateStr(currentDate.format(DateTimeFormatter.ofPattern("MM-dd")));
            dto.setTotalCount((long) dayTasks.size());

            // 统计各状态工单数量
            long completedCount = dayTasks.stream()
                    .filter(task -> "COMPLETED".equals(task.getStatus()))
                    .count();
            long inProgressCount = dayTasks.stream()
                    .filter(task -> "IN_PROGRESS".equals(task.getStatus()))
                    .count();
            long notStartedCount = dayTasks.stream()
                    .filter(task -> "NOT_STARTED".equals(task.getStatus()))
                    .count();
            long pausedCount = dayTasks.stream()
                    .filter(task -> "PAUSED".equals(task.getStatus()))
                    .count();
            long cancelledCount = dayTasks.stream()
                    .filter(task -> "CANCELLED".equals(task.getStatus()))
                    .count();

            dto.setCompletedCount(completedCount);
            dto.setInProgressCount(inProgressCount);
            dto.setNotStartedCount(notStartedCount);
            dto.setPausedCount(pausedCount);
            dto.setCancelledCount(cancelledCount);

            // 计算完成率
            if (dto.getTotalCount() > 0) {
                dto.setCompletionRate((double) completedCount / dto.getTotalCount() * 100);
            } else {
                dto.setCompletionRate(0.0);
            }

            // 计算按时完成率（这里简化处理，实际应该根据计划完成时间判断）
            dto.setOnTimeCompletedCount(completedCount);
            dto.setDelayedCompletedCount(0L);
            dto.setOnTimeCompletionRate(dto.getCompletionRate());

            result.add(dto);
            currentDate = currentDate.plusDays(1);
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
    public List<ProcessDefectRateDTO> getProcessDefectRateRanking(TimeRangeEnum timeRange, LocalDateTime startTime, LocalDateTime endTime, Integer limit) {
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

        // 按工序分组统计不良率
        Map<String, List<MesProduceTaskEntity>> tasksByProcess = produceTasks.stream()
                .filter(task -> task.getRouteName() != null)
                .collect(Collectors.groupingBy(MesProduceTaskEntity::getRouteName));

        List<ProcessDefectRateDTO> result = new ArrayList<>();
        int ranking = 1;

        for (Map.Entry<String, List<MesProduceTaskEntity>> entry : tasksByProcess.entrySet()) {
            String processName = entry.getKey();
            List<MesProduceTaskEntity> tasks = entry.getValue();

            ProcessDefectRateDTO dto = new ProcessDefectRateDTO();
            dto.setProcessName(processName);
            dto.setProcessCode("PROC_" + ranking);
            dto.setProcessDescription(processName + "工序");
            dto.setRanking(ranking);

            // 计算总检验数量和不良品数量
            long totalInspection = tasks.stream()
                    .mapToLong(task -> task.getCompleteNum() != null ? task.getCompleteNum().longValue() : 0L)
                    .sum();

            long defectCount = tasks.stream()
                    .mapToLong(task -> task.getUnPassNum() != null ? task.getUnPassNum().longValue() : 0L)
                    .sum();

            long qualifiedCount = totalInspection - defectCount;

            dto.setTotalInspectionCount(totalInspection);
            dto.setDefectCount(defectCount);
            dto.setQualifiedCount(qualifiedCount);

            // 计算不良率和合格率
            if (totalInspection > 0) {
                BigDecimal defectRate = BigDecimal.valueOf(defectCount)
                        .divide(BigDecimal.valueOf(totalInspection), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                BigDecimal qualifiedRate = BigDecimal.valueOf(100).subtract(defectRate);

                dto.setDefectRate(defectRate);
                dto.setDefectRateStr(defectRate.setScale(2, RoundingMode.HALF_UP) + "%");
                dto.setQualifiedRate(qualifiedRate);
                dto.setQualifiedRateStr(qualifiedRate.setScale(2, RoundingMode.HALF_UP) + "%");
            } else {
                dto.setDefectRate(BigDecimal.ZERO);
                dto.setDefectRateStr("0.00%");
                dto.setQualifiedRate(BigDecimal.valueOf(100));
                dto.setQualifiedRateStr("100.00%");
            }

            // 设置其他字段
            dto.setMainDefectType("质量问题");
            dto.setMainDefectReason("工艺参数偏差");
            dto.setTrendFlag("stable");
            dto.setChangePercentage(BigDecimal.ZERO);

            result.add(dto);
            ranking++;
        }

        // 按不良率降序排序
        result.sort((a, b) -> b.getDefectRate().compareTo(a.getDefectRate()));

        // 重新设置排名
        for (int i = 0; i < result.size(); i++) {
            result.get(i).setRanking(i + 1);
        }

        // 限制返回数量
        if (limit != null && limit > 0 && result.size() > limit) {
            result = result.subList(0, limit);
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
        List<MesProduceTaskEntity> completedTasks = produceTaskService.list(new LambdaQueryWrapper<MesProduceTaskEntity>()
                .ge(MesProduceTaskEntity::getCreateTime, startDate)
                .le(MesProduceTaskEntity::getCreateTime, endDate)
                .isNotNull(MesProduceTaskEntity::getCompleteNum));

        // 计算总出货产品数
        long currentSum = completedTasks.stream()
                .mapToLong(task -> task.getCompleteNum() != null ? task.getCompleteNum().longValue() : 0L)
                .sum();

        Map<String, Object> result = new HashMap<>();
        result.put("currentMonthSum", String.valueOf(currentSum));
        result.put("momComparison", "87%"); // 模拟数据
        result.put("momFlag", "up");
        result.put("yoyComparison", "10%"); // 模拟数据
        result.put("yoyFlag", "down");

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
        result.put("momComparison", "94%"); // 模拟数据
        result.put("momFlag", "up");
        result.put("yoyComparison", "8%"); // 模拟数据
        result.put("yoyFlag", "down");

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
                .isNull(MesProduceTaskEntity::getCompleteNum));

        long currentSum = wipTasks.size();

        Map<String, Object> result = new HashMap<>();
        result.put("currentMonthSum", String.valueOf(currentSum));
        result.put("momComparison", "10%"); // 模拟数据
        result.put("momFlag", "up");
        result.put("yoyComparison", "9%"); // 模拟数据
        result.put("yoyFlag", "up");

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
                .le(MesProduceTaskEntity::getCreateTime, endDate)
                .isNotNull(MesProduceTaskEntity::getUnPassNum));

        // 计算总不良品数
        long currentSum = produceTasks.stream()
                .mapToLong(task -> task.getUnPassNum() != null ? task.getUnPassNum().longValue() : 0L)
                .sum();

        Map<String, Object> result = new HashMap<>();
        result.put("currentMonthSum", String.valueOf(currentSum));
        result.put("momComparison", "57%"); // 模拟数据
        result.put("momFlag", "down");
        result.put("yoyComparison", "1%"); // 模拟数据
        result.put("yoyFlag", "down");

        return result;
    }

    /**
     * 获取工单实时进度列表（支持时间范围）
     */
    @Override
    public List<MesTaskProcessVO> getTaskProgressList(TimeRangeEnum timeRange, LocalDateTime startTime, LocalDateTime endTime, Integer limit) {
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
